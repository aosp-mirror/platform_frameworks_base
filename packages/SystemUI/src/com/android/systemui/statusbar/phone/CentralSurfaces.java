/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS;

import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
import static androidx.lifecycle.Lifecycle.State.RESUMED;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;
import static com.android.systemui.charging.WirelessChargingLayout.UNKNOWN_BATTERY_LEVEL;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.PERMISSION_SELF;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.TaskInfo;
import android.app.TaskStackBuilder;
import android.app.UiModeManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.devicestate.DeviceStateManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.IRemoteAnimationRunner;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.widget.DateTimeView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.CoreStartable;
import com.android.systemui.DejankUtils;
import com.android.systemui.EventLogTags;
import com.android.systemui.InitController;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DelegateLaunchAnimatorController;
import com.android.systemui.animation.RemoteTransitionAdapter;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.charging.WirelessChargingAnimation;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.emergency.EmergencyGesture;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.ExtensionFragmentListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.CircleReveal;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LiftReveal;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.PowerButtonReveal;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.charging.WiredChargingRippleController;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.core.StatusBarInitializer;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneModule;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.util.DumpUtilsKt;
import com.android.systemui.util.WallpaperController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.MessageRouter;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.startingsurface.SplashscreenContentDrawer;
import com.android.wm.shell.startingsurface.StartingSurface;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Lazy;

/**
 * A class handling initialization and coordination between some of the key central surfaces in
 * System UI: The notification shade, the keyguard (lockscreen), and the status bar.
 *
 * This class is not our ideal architecture because it doesn't enforce much isolation between these
 * three mostly disparate surfaces. In an ideal world, this class would not exist. Instead, we would
 * break it up into three modules -- one for each of those three surfaces -- and we would define any
 * APIs that are needed for these surfaces to communicate with each other when necessary.
 *
 * <b>If at all possible, please avoid adding additional code to this monstrous class! Our goal is
 * to break up this class into many small classes, and any code added here will slow down that goal.
 * </b>
 */
public class CentralSurfaces extends CoreStartable implements
        ActivityStarter,
        LifecycleOwner {
    public static final boolean MULTIUSER_DEBUG = false;

    protected static final int MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU = 1027;

    // Should match the values in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    public static final String SYSTEM_DIALOG_REASON_DREAM = "dream";
    static public final String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";

    private static final String BANNER_ACTION_CANCEL =
            "com.android.systemui.statusbar.banner_action_cancel";
    private static final String BANNER_ACTION_SETUP =
            "com.android.systemui.statusbar.banner_action_setup";
    public static final String TAG = "CentralSurfaces";
    public static final boolean DEBUG = false;
    public static final boolean SPEW = false;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info
    public static final boolean DEBUG_GESTURES = false;
    public static final boolean DEBUG_MEDIA_FAKE_ARTWORK = false;
    public static final boolean DEBUG_CAMERA_LIFT = false;

    public static final boolean DEBUG_WINDOW_STATE = false;

    // additional instrumentation for testing purposes; intended to be left on during development
    public static final boolean CHATTY = DEBUG;

    public static final boolean SHOW_LOCKSCREEN_MEDIA_ARTWORK = true;

    public static final String ACTION_FAKE_ARTWORK = "fake_artwork";

    private static final int MSG_OPEN_SETTINGS_PANEL = 1002;
    private static final int MSG_LAUNCH_TRANSITION_TIMEOUT = 1003;
    // 1020-1040 reserved for BaseStatusBar

    // Time after we abort the launch transition.
    static final long LAUNCH_TRANSITION_TIMEOUT_MS = 5000;

    protected static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    /**
     * The delay to reset the hint text when the hint animation is finished running.
     */
    private static final int HINT_RESET_DELAY_MS = 1200;

    public static final int FADE_KEYGUARD_START_DELAY = 100;
    public static final int FADE_KEYGUARD_DURATION = 300;
    public static final int FADE_KEYGUARD_DURATION_PULSING = 96;

    public static final long[] CAMERA_LAUNCH_GESTURE_VIBRATION_TIMINGS =
            new long[]{20, 20, 20, 20, 100, 20};
    public static final int[] CAMERA_LAUNCH_GESTURE_VIBRATION_AMPLITUDES =
            new int[]{39, 82, 139, 213, 0, 127};

    /**
     * If true, the system is in the half-boot-to-decryption-screen state.
     * Prudently disable QS and notifications.
     */
    public static final boolean ONLY_CORE_APPS;

    /** If true, the lockscreen will show a distinct wallpaper */
    public static final boolean ENABLE_LOCKSCREEN_WALLPAPER = true;

    private static final UiEventLogger sUiEventLogger = new UiEventLoggerImpl();

    static {
        boolean onlyCoreApps;
        try {
            IPackageManager packageManager =
                    IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            onlyCoreApps = packageManager != null && packageManager.isOnlyCoreApps();
        } catch (RemoteException e) {
            onlyCoreApps = false;
        }
        ONLY_CORE_APPS = onlyCoreApps;
    }

    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final DreamOverlayStateController mDreamOverlayStateController;
    private CentralSurfacesCommandQueueCallbacks mCommandQueueCallbacks;
    private float mTransitionToFullShadeProgress = 0f;
    private NotificationListContainer mNotifListContainer;

    private final KeyguardStateController.Callback mKeyguardStateControllerCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    boolean occluded = mKeyguardStateController.isOccluded();
                    mStatusBarHideIconsForBouncerManager.setIsOccludedAndTriggerUpdate(occluded);
                    mScrimController.setKeyguardOccluded(occluded);
                }
            };

    void onStatusBarWindowStateChanged(@WindowVisibleState int state) {
        updateBubblesVisibility();
        mStatusBarWindowState = state;
    }

    void acquireGestureWakeLock(long time) {
        mGestureWakeLock.acquire(time);
    }

    boolean setAppearance(int appearance) {
        if (mAppearance != appearance) {
            mAppearance = appearance;
            return updateBarMode(barMode(isTransientShown(), appearance));
        }

        return false;
    }

    int getBarMode() {
        return mStatusBarMode;
    }

    void resendMessage(int msg) {
        mMessageRouter.cancelMessages(msg);
        mMessageRouter.sendMessage(msg);
    }

    void resendMessage(Object msg) {
        mMessageRouter.cancelMessages(msg.getClass());
        mMessageRouter.sendMessage(msg);
    }

    int getDisabled1() {
        return mDisabled1;
    }

    void setDisabled1(int disabled) {
        mDisabled1 = disabled;
    }

    int getDisabled2() {
        return mDisabled2;
    }

    void setDisabled2(int disabled) {
        mDisabled2 = disabled;
    }

    void setLastCameraLaunchSource(int source) {
        mLastCameraLaunchSource = source;
    }

    void setLaunchCameraOnFinishedGoingToSleep(boolean launch) {
        mLaunchCameraOnFinishedGoingToSleep = launch;
    }

    void setLaunchCameraOnFinishedWaking(boolean launch) {
        mLaunchCameraWhenFinishedWaking = launch;
    }

    void setLaunchEmergencyActionOnFinishedGoingToSleep(boolean launch) {
        mLaunchEmergencyActionOnFinishedGoingToSleep = launch;
    }

    void setLaunchEmergencyActionOnFinishedWaking(boolean launch) {
        mLaunchEmergencyActionWhenFinishedWaking = launch;
    }

    void setTopHidesStatusBar(boolean hides) {
        mTopHidesStatusBar = hides;
    }

    QSPanelController getQSPanelController() {
        return mQSPanelController;
    }

    /** */
    public void animateExpandNotificationsPanel() {
        mCommandQueueCallbacks.animateExpandNotificationsPanel();
    }

    /** */
    public void animateExpandSettingsPanel(@Nullable String subpanel) {
        mCommandQueueCallbacks.animateExpandSettingsPanel(subpanel);
    }

    /** */
    public void animateCollapsePanels(int flags, boolean force) {
        mCommandQueueCallbacks.animateCollapsePanels(flags, force);
    }

    /** */
    public void togglePanel() {
        mCommandQueueCallbacks.togglePanel();
    }
    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState; // TODO: remove this. Just use StatusBarStateController
    protected boolean mBouncerShowing;
    private boolean mBouncerShowingOverDream;

    private final PhoneStatusBarPolicy mIconPolicy;

    private final VolumeComponent mVolumeComponent;
    private BrightnessMirrorController mBrightnessMirrorController;
    private boolean mBrightnessMirrorVisible;
    private BiometricUnlockController mBiometricUnlockController;
    private final LightBarController mLightBarController;
    private final Lazy<LockscreenWallpaper> mLockscreenWallpaperLazy;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    @Nullable
    protected LockscreenWallpaper mLockscreenWallpaper;
    private final AutoHideController mAutoHideController;

    private final Point mCurrentDisplaySize = new Point();

    protected NotificationShadeWindowView mNotificationShadeWindowView;
    protected PhoneStatusBarView mStatusBarView;
    private PhoneStatusBarViewController mPhoneStatusBarViewController;
    private PhoneStatusBarTransitions mStatusBarTransitions;
    private AuthRippleController mAuthRippleController;
    @WindowVisibleState private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    protected final NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarWindowController mStatusBarWindowController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @VisibleForTesting
    DozeServiceHost mDozeServiceHost;
    private boolean mWakeUpComingFromTouch;
    private PointF mWakeUpTouchLocation;
    private LightRevealScrim mLightRevealScrim;
    private PowerButtonReveal mPowerButtonReveal;

    private final Object mQueueLock = new Object();

    private final PulseExpansionHandler mPulseExpansionHandler;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardStateController mKeyguardStateController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ConfigurationController mConfigurationController;
    protected NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private final DozeParameters mDozeParameters;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final CentralSurfacesComponent.Factory mCentralSurfacesComponentFactory;
    private final PluginManager mPluginManager;
    private final ShadeController mShadeController;
    private final InitController mInitController;

    private final PluginDependencyProvider mPluginDependencyProvider;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final ExtensionController mExtensionController;
    private final UserInfoControllerImpl mUserInfoControllerImpl;
    private final DemoModeController mDemoModeController;
    private final NotificationsController mNotificationsController;
    private final OngoingCallController mOngoingCallController;
    private final StatusBarSignalPolicy mStatusBarSignalPolicy;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;

    // expanded notifications
    // the sliding/resizing panel within the notification window
    protected NotificationPanelViewController mNotificationPanelViewController;

    // settings
    private QSPanelController mQSPanelController;

    KeyguardIndicationController mKeyguardIndicationController;

    private View mReportRejectedTouch;

    private boolean mExpandedVisible;

    private final int[] mAbsPos = new int[2];

    private final NotifShadeEventSource mNotifShadeEventSource;
    protected final NotificationEntryManager mEntryManager;
    private final NotificationGutsManager mGutsManager;
    private final NotificationLogger mNotificationLogger;
    private final NotificationViewHierarchyManager mViewHierarchyManager;
    private final PanelExpansionStateManager mPanelExpansionStateManager;
    private final KeyguardViewMediator mKeyguardViewMediator;
    protected final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final BrightnessSliderController.Factory mBrightnessSliderFactory;
    private final FeatureFlags mFeatureFlags;
    private final FragmentService mFragmentService;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final WallpaperController mWallpaperController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final MessageRouter mMessageRouter;
    private final WallpaperManager mWallpaperManager;

    private CentralSurfacesComponent mCentralSurfacesComponent;

    // Flags for disabling the status bar
    // Two variables becaseu the first one evidently ran out of room for new flags.
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int, int) */
    private @Appearance int mAppearance;

    private boolean mTransientShown;

    private final DisplayMetrics mDisplayMetrics;

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private final ScreenPinningRequest mScreenPinningRequest;

    private final MetricsLogger mMetricsLogger;

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    @VisibleForTesting
    protected boolean mUserSetup = false;

    @VisibleForTesting
    public enum StatusBarUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Secured lockscreen is opened.")
        LOCKSCREEN_OPEN_SECURE(405),

        @UiEvent(doc = "Lockscreen without security is opened.")
        LOCKSCREEN_OPEN_INSECURE(406),

        @UiEvent(doc = "Secured lockscreen is closed.")
        LOCKSCREEN_CLOSE_SECURE(407),

        @UiEvent(doc = "Lockscreen without security is closed.")
        LOCKSCREEN_CLOSE_INSECURE(408),

        @UiEvent(doc = "Secured bouncer is opened.")
        BOUNCER_OPEN_SECURE(409),

        @UiEvent(doc = "Bouncer without security is opened.")
        BOUNCER_OPEN_INSECURE(410),

        @UiEvent(doc = "Secured bouncer is closed.")
        BOUNCER_CLOSE_SECURE(411),

        @UiEvent(doc = "Bouncer without security is closed.")
        BOUNCER_CLOSE_INSECURE(412);

        private final int mId;

        StatusBarUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private Handler mMainHandler;
    private final DelayableExecutor mMainExecutor;

    private int mInteractingWindows;
    private @TransitionMode int mStatusBarMode;

    private final ViewMediatorCallback mKeyguardViewMediatorCallback;
    private final ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;
    private final Executor mUiBgExecutor;

    protected boolean mDozing;
    private boolean mIsFullscreen;

    boolean mCloseQsBeforeScreenOff;

    private final NotificationMediaManager mMediaManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private boolean mWallpaperSupported;

    private Runnable mLaunchTransitionEndRunnable;
    private Runnable mLaunchTransitionCancelRunnable;
    private boolean mLaunchCameraWhenFinishedWaking;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
    private boolean mLaunchEmergencyActionWhenFinishedWaking;
    private boolean mLaunchEmergencyActionOnFinishedGoingToSleep;
    private int mLastCameraLaunchSource;
    protected PowerManager.WakeLock mGestureWakeLock;

    private final int[] mTmpInt2 = new int[2];

    // Fingerprint (as computed by getLoggingFingerprint() of the last logged state.
    private int mLastLoggedStateFingerprint;
    private boolean mTopHidesStatusBar;
    private boolean mStatusBarWindowHidden;
    private boolean mIsLaunchingActivityOverLockscreen;

    private final UserSwitcherController mUserSwitcherController;
    private final NetworkController mNetworkController;
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
    protected final BatteryController mBatteryController;
    protected boolean mPanelExpanded;
    private UiModeManager mUiModeManager;
    private LogMaker mStatusBarStateLog;
    protected final NotificationIconAreaController mNotificationIconAreaController;
    @Nullable private View mAmbientIndicationContainer;
    private final SysuiColorExtractor mColorExtractor;
    private final ScreenLifecycle mScreenLifecycle;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    private boolean mNoAnimationOnNextBarModeChange;
    private final SysuiStatusBarStateController mStatusBarStateController;

    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    protected NotificationPresenter mPresenter;
    private NotificationActivityStarter mNotificationActivityStarter;
    private final Lazy<NotificationShadeDepthController> mNotificationShadeDepthControllerLazy;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final Optional<Bubbles> mBubblesOptional;
    private final Bubbles.BubbleExpandListener mBubbleExpandListener;
    private final Optional<StartingSurface> mStartingSurfaceOptional;
    private final NotifPipelineFlags mNotifPipelineFlags;

    private final ActivityIntentHelper mActivityIntentHelper;
    private NotificationStackScrollLayoutController mStackScrollerController;

    private final ColorExtractor.OnColorsChangedListener mOnColorsChangedListener =
            (extractor, which) -> updateTheme();

    private final InteractionJankMonitor mJankMonitor;


    /**
     * Public constructor for CentralSurfaces.
     *
     * CentralSurfaces is considered optional, and therefore can not be marked as @Inject directly.
     * Instead, an @Provide method is included. See {@link StatusBarPhoneModule}.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CentralSurfaces(
            Context context,
            NotificationsController notificationsController,
            FragmentService fragmentService,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            StatusBarWindowController statusBarWindowController,
            StatusBarWindowStateController statusBarWindowStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            StatusBarSignalPolicy statusBarSignalPolicy,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManagerPhone headsUpManagerPhone,
            DynamicPrivacyController dynamicPrivacyController,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            BroadcastDispatcher broadcastDispatcher,
            NotifShadeEventSource notifShadeEventSource,
            NotificationEntryManager notificationEntryManager,
            NotificationGutsManager notificationGutsManager,
            NotificationLogger notificationLogger,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationViewHierarchyManager notificationViewHierarchyManager,
            PanelExpansionStateManager panelExpansionStateManager,
            KeyguardViewMediator keyguardViewMediator,
            DisplayMetrics displayMetrics,
            MetricsLogger metricsLogger,
            @UiBackground Executor uiBgExecutor,
            NotificationMediaManager notificationMediaManager,
            NotificationLockscreenUserManager lockScreenUserManager,
            NotificationRemoteInputManager remoteInputManager,
            UserSwitcherController userSwitcherController,
            NetworkController networkController,
            BatteryController batteryController,
            SysuiColorExtractor colorExtractor,
            ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle,
            SysuiStatusBarStateController statusBarStateController,
            Optional<BubblesManager> bubblesManagerOptional,
            Optional<Bubbles> bubblesOptional,
            VisualStabilityManager visualStabilityManager,
            DeviceProvisionedController deviceProvisionedController,
            NavigationBarController navigationBarController,
            AccessibilityFloatingMenuController accessibilityFloatingMenuController,
            Lazy<AssistManager> assistManagerLazy,
            ConfigurationController configurationController,
            NotificationShadeWindowController notificationShadeWindowController,
            DozeParameters dozeParameters,
            ScrimController scrimController,
            Lazy<LockscreenWallpaper> lockscreenWallpaperLazy,
            LockscreenGestureLogger lockscreenGestureLogger,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            ScreenPinningRequest screenPinningRequest,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            CentralSurfacesComponent.Factory centralSurfacesComponentFactory,
            PluginManager pluginManager,
            ShadeController shadeController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ViewMediatorCallback viewMediatorCallback,
            InitController initController,
            @Named(TIME_TICK_HANDLER_NAME) Handler timeTickHandler,
            PluginDependencyProvider pluginDependencyProvider,
            KeyguardDismissUtil keyguardDismissUtil,
            ExtensionController extensionController,
            UserInfoControllerImpl userInfoControllerImpl,
            PhoneStatusBarPolicy phoneStatusBarPolicy,
            KeyguardIndicationController keyguardIndicationController,
            DemoModeController demoModeController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthControllerLazy,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            NotificationIconAreaController notificationIconAreaController,
            BrightnessSliderController.Factory brightnessSliderFactory,
            ScreenOffAnimationController screenOffAnimationController,
            WallpaperController wallpaperController,
            OngoingCallController ongoingCallController,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            FeatureFlags featureFlags,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            @Main Handler mainHandler,
            @Main DelayableExecutor delayableExecutor,
            @Main MessageRouter messageRouter,
            WallpaperManager wallpaperManager,
            Optional<StartingSurface> startingSurfaceOptional,
            ActivityLaunchAnimator activityLaunchAnimator,
            NotifPipelineFlags notifPipelineFlags,
            InteractionJankMonitor jankMonitor,
            DeviceStateManager deviceStateManager,
            DreamOverlayStateController dreamOverlayStateController,
            WiredChargingRippleController wiredChargingRippleController) {
        super(context);
        mNotificationsController = notificationsController;
        mFragmentService = fragmentService;
        mLightBarController = lightBarController;
        mAutoHideController = autoHideController;
        mStatusBarWindowController = statusBarWindowController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPulseExpansionHandler = pulseExpansionHandler;
        mWakeUpCoordinator = notificationWakeUpCoordinator;
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManagerPhone;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mDynamicPrivacyController = dynamicPrivacyController;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mNotifShadeEventSource = notifShadeEventSource;
        mEntryManager = notificationEntryManager;
        mGutsManager = notificationGutsManager;
        mNotificationLogger = notificationLogger;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mViewHierarchyManager = notificationViewHierarchyManager;
        mPanelExpansionStateManager = panelExpansionStateManager;
        mKeyguardViewMediator = keyguardViewMediator;
        mDisplayMetrics = displayMetrics;
        mMetricsLogger = metricsLogger;
        mUiBgExecutor = uiBgExecutor;
        mMediaManager = notificationMediaManager;
        mLockscreenUserManager = lockScreenUserManager;
        mRemoteInputManager = remoteInputManager;
        mUserSwitcherController = userSwitcherController;
        mNetworkController = networkController;
        mBatteryController = batteryController;
        mColorExtractor = colorExtractor;
        mScreenLifecycle = screenLifecycle;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mBubblesManagerOptional = bubblesManagerOptional;
        mBubblesOptional = bubblesOptional;
        mVisualStabilityManager = visualStabilityManager;
        mDeviceProvisionedController = deviceProvisionedController;
        mNavigationBarController = navigationBarController;
        mAccessibilityFloatingMenuController = accessibilityFloatingMenuController;
        mAssistManagerLazy = assistManagerLazy;
        mConfigurationController = configurationController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mDozeServiceHost = dozeServiceHost;
        mPowerManager = powerManager;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mLockscreenWallpaperLazy = lockscreenWallpaperLazy;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mScreenPinningRequest = screenPinningRequest;
        mDozeScrimController = dozeScrimController;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mNotificationShadeDepthControllerLazy = notificationShadeDepthControllerLazy;
        mVolumeComponent = volumeComponent;
        mCommandQueue = commandQueue;
        mCentralSurfacesComponentFactory = centralSurfacesComponentFactory;
        mPluginManager = pluginManager;
        mShadeController = shadeController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardViewMediatorCallback = viewMediatorCallback;
        mInitController = initController;
        mPluginDependencyProvider = pluginDependencyProvider;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mExtensionController = extensionController;
        mUserInfoControllerImpl = userInfoControllerImpl;
        mIconPolicy = phoneStatusBarPolicy;
        mDemoModeController = demoModeController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBrightnessSliderFactory = brightnessSliderFactory;
        mWallpaperController = wallpaperController;
        mOngoingCallController = ongoingCallController;
        mStatusBarSignalPolicy = statusBarSignalPolicy;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mFeatureFlags = featureFlags;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mMainHandler = mainHandler;
        mMainExecutor = delayableExecutor;
        mMessageRouter = messageRouter;
        mWallpaperManager = wallpaperManager;
        mJankMonitor = jankMonitor;
        mDreamOverlayStateController = dreamOverlayStateController;

        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mStartingSurfaceOptional = startingSurfaceOptional;
        mNotifPipelineFlags = notifPipelineFlags;
        lockscreenShadeTransitionController.setCentralSurfaces(this);
        statusBarWindowStateController.addListener(this::onStatusBarWindowStateChanged);

        mScreenOffAnimationController = screenOffAnimationController;

        mPanelExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged);

        mBubbleExpandListener =
                (isExpanding, key) -> mContext.getMainExecutor().execute(() -> {
                    mNotificationsController.requestNotificationUpdate("onBubbleExpandChanged");
                    updateScrimController();
                });

        mActivityIntentHelper = new ActivityIntentHelper(mContext);
        mActivityLaunchAnimator = activityLaunchAnimator;

        // The status bar background may need updating when the ongoing call status changes.
        mOngoingCallController.addCallback((animate) -> maybeUpdateBarMode());

        // TODO(b/190746471): Find a better home for this.
        DateTimeView.setReceiverHandler(timeTickHandler);

        mMessageRouter.subscribeTo(KeyboardShortcutsMessage.class,
                data -> toggleKeyboardShortcuts(data.mDeviceId));
        mMessageRouter.subscribeTo(MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU,
                id -> dismissKeyboardShortcuts());
        mMessageRouter.subscribeTo(AnimateExpandSettingsPanelMessage.class,
                data -> mCommandQueueCallbacks.animateExpandSettingsPanel(data.mSubpanel));
        mMessageRouter.subscribeTo(MSG_LAUNCH_TRANSITION_TIMEOUT,
                id -> onLaunchTransitionTimeout());

        deviceStateManager.registerCallback(mMainExecutor,
                new FoldStateListener(mContext, this::onFoldedStateChanged));
        wiredChargingRippleController.registerCallbacks();
    }

    @Override
    public void start() {
        mScreenLifecycle.addObserver(mScreenObserver);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        if (mBubblesOptional.isPresent()) {
            mBubblesOptional.get().setExpandListener(mBubbleExpandListener);
        }

        mStatusBarSignalPolicy.init();
        mKeyguardIndicationController.init();

        mColorExtractor.addOnColorsChangedListener(mOnColorsChangedListener);
        mStatusBarStateController.addCallback(mStateListener,
                SysuiStatusBarStateController.RANK_STATUS_BAR);

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        mDisplay = mContext.getDisplay();
        mDisplayId = mDisplay.getDisplayId();
        updateDisplaySize();
        mStatusBarHideIconsForBouncerManager.setDisplayId(mDisplayId);

        // start old BaseStatusBar.start().
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mWallpaperSupported = mWallpaperManager.isWallpaperSupported();

        RegisterStatusBarResult result = null;
        try {
            result = mBarService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        createAndAddWindows(result);

        if (mWallpaperSupported) {
            // Make sure we always have the most current wallpaper info.
            IntentFilter wallpaperChangedFilter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
            mBroadcastDispatcher.registerReceiver(mWallpaperChangedReceiver, wallpaperChangedFilter,
                    null /* handler */, UserHandle.ALL);
            mWallpaperChangedReceiver.onReceive(mContext, null);
        } else if (DEBUG) {
            Log.v(TAG, "start(): no wallpaper service ");
        }

        // Set up the initial notification state. This needs to happen before CommandQueue.disable()
        setUpPresenter();

        if (containsType(result.mTransientBarTypes, ITYPE_STATUS_BAR)) {
            showTransientUnchecked();
        }
        mCommandQueueCallbacks.onSystemBarAttributesChanged(mDisplayId, result.mAppearance,
                result.mAppearanceRegions, result.mNavbarColorManagedByIme, result.mBehavior,
                result.mRequestedVisibilities, result.mPackageName);

        // StatusBarManagerService has a back up of IME token and it's restored here.
        mCommandQueueCallbacks.setImeWindowStatus(mDisplayId, result.mImeToken,
                result.mImeWindowVis, result.mImeBackDisposition, result.mShowImeSwitcher);

        // Set up the initial icon state
        int numIcons = result.mIcons.size();
        for (int i = 0; i < numIcons; i++) {
            mCommandQueue.setIcon(result.mIcons.keyAt(i), result.mIcons.valueAt(i));
        }

        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x imeButton=0x%08x",
                    numIcons,
                    result.mDisabledFlags1,
                    result.mAppearance,
                    result.mImeWindowVis));
        }

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(BANNER_ACTION_CANCEL);
        internalFilter.addAction(BANNER_ACTION_SETUP);
        mContext.registerReceiver(mBannerActionBroadcastReceiver, internalFilter, PERMISSION_SELF,
                null, Context.RECEIVER_EXPORTED_UNAUDITED);

        if (mWallpaperSupported) {
            IWallpaperManager wallpaperManager = IWallpaperManager.Stub.asInterface(
                    ServiceManager.getService(Context.WALLPAPER_SERVICE));
            try {
                wallpaperManager.setInAmbientMode(false /* ambientMode */, 0L /* duration */);
            } catch (RemoteException e) {
                // Just pass, nothing critical.
            }
        }

        // end old BaseStatusBar.start().

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy.init();

        mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onUnlockedChanged() {
                logStateToEventlog();
            }
        });
        startKeyguard();

        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
        mDozeServiceHost.initialize(
                this,
                mStatusBarKeyguardViewManager,
                mNotificationShadeWindowViewController,
                mNotificationPanelViewController,
                mAmbientIndicationContainer);
        updateLightRevealScrimVisibility();

        mConfigurationController.addCallback(mConfigurationListener);

        mBatteryController.observe(mLifecycle, mBatteryStateChangeCallback);
        mLifecycle.setCurrentState(RESUMED);

        mAccessibilityFloatingMenuController.init();

        // set the initial view visibility
        int disabledFlags1 = result.mDisabledFlags1;
        int disabledFlags2 = result.mDisabledFlags2;
        mInitController.addPostInitTask(
                () -> setUpDisableFlags(disabledFlags1, disabledFlags2));

        mFalsingManager.addFalsingBeliefListener(mFalsingBeliefListener);

        mPluginManager.addPluginListener(
                new PluginListener<OverlayPlugin>() {
                    private final ArraySet<OverlayPlugin> mOverlays = new ArraySet<>();

                    @Override
                    public void onPluginConnected(OverlayPlugin plugin, Context pluginContext) {
                        mMainExecutor.execute(
                                () -> plugin.setup(getNotificationShadeWindowView(),
                                        getNavigationBarView(),
                                        new Callback(plugin), mDozeParameters));
                    }

                    @Override
                    public void onPluginDisconnected(OverlayPlugin plugin) {
                        mMainExecutor.execute(() -> {
                            mOverlays.remove(plugin);
                            mNotificationShadeWindowController
                                    .setForcePluginOpen(mOverlays.size() != 0, this);
                        });
                    }

                    class Callback implements OverlayPlugin.Callback {
                        private final OverlayPlugin mPlugin;

                        Callback(OverlayPlugin plugin) {
                            mPlugin = plugin;
                        }

                        @Override
                        public void onHoldStatusBarOpenChange() {
                            if (mPlugin.holdStatusBarOpen()) {
                                mOverlays.add(mPlugin);
                            } else {
                                mOverlays.remove(mPlugin);
                            }
                            mMainExecutor.execute(() -> {
                                mNotificationShadeWindowController
                                        .setStateListener(b -> mOverlays.forEach(
                                                o -> o.setCollapseDesired(b)));
                                mNotificationShadeWindowController
                                        .setForcePluginOpen(mOverlays.size() != 0, this);
                            });
                        }
                    }
                }, OverlayPlugin.class, true /* Allow multiple plugins */);

        mStartingSurfaceOptional.ifPresent(startingSurface -> startingSurface.setSysuiProxy(
                (requestTopUi, componentTag) -> mMainExecutor.execute(() ->
                        mNotificationShadeWindowController.setRequestTopUi(
                                requestTopUi, componentTag))));
    }

    private void onFoldedStateChanged(boolean isFolded, boolean willGoToSleep) {
        Trace.beginSection("CentralSurfaces#onFoldedStateChanged");
        onFoldedStateChangedInternal(isFolded, willGoToSleep);
        Trace.endSection();
    }

    private void onFoldedStateChangedInternal(boolean isFolded, boolean willGoToSleep) {
        // Folded state changes are followed by a screen off event.
        // By default turning off the screen also closes the shade.
        // We want to make sure that the shade status is kept after
        // folding/unfolding.
        boolean isShadeOpen = mShadeController.isShadeOpen();
        boolean leaveOpen = isShadeOpen && !willGoToSleep;
        if (DEBUG) {
            Log.d(TAG, String.format(
                    "#onFoldedStateChanged(): "
                            + "isFolded=%s, "
                            + "willGoToSleep=%s, "
                            + "isShadeOpen=%s, "
                            + "leaveOpen=%s",
                    isFolded, willGoToSleep, isShadeOpen, leaveOpen));
        }
        if (leaveOpen) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
            if (mKeyguardStateController.isShowing()) {
                // When device state changes on keyguard we don't want to keep the state of
                // the shade and instead we open clean state of keyguard with shade closed.
                // Normally some parts of QS state (like expanded/collapsed) are persisted and
                // that causes incorrect UI rendering, especially when changing state with QS
                // expanded. To prevent that we can close QS which resets QS and some parts of
                // the shade to its default state. Read more in b/201537421
                mCloseQsBeforeScreenOff = true;
            }
        }
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
        updateDisplaySize(); // populates mDisplayMetrics
        updateResources();
        updateTheme();

        inflateStatusBarWindow();
        mNotificationShadeWindowView.setOnTouchListener(getStatusBarWindowTouchListener());
        mWallpaperController.setRootView(mNotificationShadeWindowView);

        // TODO: Deal with the ugliness that comes from having some of the status bar broken out
        // into fragments, but the rest here, it leaves some awkward lifecycle and whatnot.
        mNotificationLogger.setUpWithContainer(mNotifListContainer);
        mNotificationIconAreaController.setupShelf(mNotificationShelfController);
        mPanelExpansionStateManager.addExpansionListener(mWakeUpCoordinator);
        mUserSwitcherController.init(mNotificationShadeWindowView);

        // Allow plugins to reference DarkIconDispatcher and StatusBarStateController
        mPluginDependencyProvider.allowPluginDependency(DarkIconDispatcher.class);
        mPluginDependencyProvider.allowPluginDependency(StatusBarStateController.class);

        // Set up CollapsedStatusBarFragment and PhoneStatusBarView
        StatusBarInitializer initializer = mCentralSurfacesComponent.getStatusBarInitializer();
        initializer.setStatusBarViewUpdatedListener(
                (statusBarView, statusBarViewController, statusBarTransitions) -> {
                    mStatusBarView = statusBarView;
                    mPhoneStatusBarViewController = statusBarViewController;
                    mStatusBarTransitions = statusBarTransitions;
                    mNotificationShadeWindowViewController
                            .setStatusBarViewController(mPhoneStatusBarViewController);
                    // Ensure we re-propagate panel expansion values to the panel controller and
                    // any listeners it may have, such as PanelBar. This will also ensure we
                    // re-display the notification panel if necessary (for example, if
                    // a heads-up notification was being displayed and should continue being
                    // displayed).
                    mNotificationPanelViewController.updatePanelExpansionAndVisibility();
                    setBouncerShowingForStatusBarComponents(mBouncerShowing);
                    checkBarModes();
                });
        initializer.initializeStatusBar(mCentralSurfacesComponent);

        mStatusBarTouchableRegionManager.setup(this, mNotificationShadeWindowView);
        mHeadsUpManager.addListener(mNotificationPanelViewController.getOnHeadsUpChangedListener());
        if (!mNotifPipelineFlags.isNewPipelineEnabled()) {
            mHeadsUpManager.addListener(mVisualStabilityManager);
        }
        mNotificationPanelViewController.setHeadsUpManager(mHeadsUpManager);

        createNavigationBar(result);

        if (ENABLE_LOCKSCREEN_WALLPAPER && mWallpaperSupported) {
            mLockscreenWallpaper = mLockscreenWallpaperLazy.get();
        }

        mNotificationPanelViewController.setKeyguardIndicationController(
                mKeyguardIndicationController);

        mAmbientIndicationContainer = mNotificationShadeWindowView.findViewById(
                R.id.ambient_indication_container);

        mAutoHideController.setStatusBar(new AutoHideUiElement() {
            @Override
            public void synchronizeState() {
                checkBarModes();
            }

            @Override
            public boolean shouldHideOnTouch() {
                return !mRemoteInputManager.isRemoteInputActive();
            }

            @Override
            public boolean isVisible() {
                return isTransientShown();
            }

            @Override
            public void hide() {
                clearTransient();
            }
        });

        ScrimView scrimBehind = mNotificationShadeWindowView.findViewById(R.id.scrim_behind);
        ScrimView notificationsScrim = mNotificationShadeWindowView
                .findViewById(R.id.scrim_notifications);
        ScrimView scrimInFront = mNotificationShadeWindowView.findViewById(R.id.scrim_in_front);

        mScrimController.setScrimVisibleListener(scrimsVisible -> {
            mNotificationShadeWindowController.setScrimsVisibility(scrimsVisible);
        });
        mScrimController.attachViews(scrimBehind, notificationsScrim, scrimInFront);

        mLightRevealScrim = mNotificationShadeWindowView.findViewById(R.id.light_reveal_scrim);
        mLightRevealScrim.setScrimOpaqueChangedListener((opaque) -> {
            Runnable updateOpaqueness = () -> {
                mNotificationShadeWindowController.setLightRevealScrimOpaque(
                        mLightRevealScrim.isScrimOpaque());
                mScreenOffAnimationController
                        .onScrimOpaqueChanged(mLightRevealScrim.isScrimOpaque());
            };
            if (opaque) {
                // Delay making the view opaque for a frame, because it needs some time to render
                // otherwise this can lead to a flicker where the scrim doesn't cover the screen
                mLightRevealScrim.post(updateOpaqueness);
            } else {
                updateOpaqueness.run();
            }
        });

        mScreenOffAnimationController.initialize(this, mLightRevealScrim);
        updateLightRevealScrimVisibility();

        mNotificationPanelViewController.initDependencies(
                this,
                this::makeExpandedInvisible,
                mNotificationShelfController);

        BackDropView backdrop = mNotificationShadeWindowView.findViewById(R.id.backdrop);
        mMediaManager.setup(backdrop, backdrop.findViewById(R.id.backdrop_front),
                backdrop.findViewById(R.id.backdrop_back), mScrimController, mLockscreenWallpaper);
        float maxWallpaperZoom = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_wallpaperMaxScale);
        mNotificationShadeDepthControllerLazy.get().addListener(depth -> {
            float scale = MathUtils.lerp(maxWallpaperZoom, 1f, depth);
            backdrop.setPivotX(backdrop.getWidth() / 2f);
            backdrop.setPivotY(backdrop.getHeight() / 2f);
            backdrop.setScaleX(scale);
            backdrop.setScaleY(scale);
        });

        mNotificationPanelViewController.setUserSetupComplete(mUserSetup);

        // Set up the quick settings tile panel
        final View container = mNotificationShadeWindowView.findViewById(R.id.qs_frame);
        if (container != null) {
            FragmentHostManager fragmentHostManager = FragmentHostManager.get(container);
            ExtensionFragmentListener.attachExtensonToFragment(container, QS.TAG, R.id.qs_frame,
                    mExtensionController
                            .newExtension(QS.class)
                            .withPlugin(QS.class)
                            .withDefault(this::createDefaultQSFragment)
                            .build());
            mBrightnessMirrorController = new BrightnessMirrorController(
                    mNotificationShadeWindowView,
                    mNotificationPanelViewController,
                    mNotificationShadeDepthControllerLazy.get(),
                    mBrightnessSliderFactory,
                    (visible) -> {
                        mBrightnessMirrorVisible = visible;
                        updateScrimController();
                    });
            fragmentHostManager.addTagListener(QS.TAG, (tag, f) -> {
                QS qs = (QS) f;
                if (qs instanceof QSFragment) {
                    mQSPanelController = ((QSFragment) qs).getQSPanelController();
                    ((QSFragment) qs).setBrightnessMirrorController(mBrightnessMirrorController);
                }
            });
        }

        mReportRejectedTouch = mNotificationShadeWindowView
                .findViewById(R.id.report_rejected_touch);
        if (mReportRejectedTouch != null) {
            updateReportRejectedTouchVisibility();
            mReportRejectedTouch.setOnClickListener(v -> {
                Uri session = mFalsingManager.reportRejectedTouch();
                if (session == null) { return; }

                StringWriter message = new StringWriter();
                message.write("Build info: ");
                message.write(SystemProperties.get("ro.build.description"));
                message.write("\nSerial number: ");
                message.write(SystemProperties.get("ro.serialno"));
                message.write("\n");

                startActivityDismissingKeyguard(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                                .setType("*/*")
                                .putExtra(Intent.EXTRA_SUBJECT, "Rejected touch report")
                                .putExtra(Intent.EXTRA_STREAM, session)
                                .putExtra(Intent.EXTRA_TEXT, message.toString()),
                        "Share rejected touch report")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        true /* onlyProvisioned */, true /* dismissShade */);
            });
        }

        if (!mPowerManager.isInteractive()) {
            mBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        }
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "sysui:GestureWakeLock");

        // receive broadcasts
        registerBroadcastReceiver();

        IntentFilter demoFilter = new IntentFilter();
        if (DEBUG_MEDIA_FAKE_ARTWORK) {
            demoFilter.addAction(ACTION_FAKE_ARTWORK);
        }
        mContext.registerReceiverAsUser(mDemoReceiver, UserHandle.ALL, demoFilter,
                android.Manifest.permission.DUMP, null,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        mDeviceProvisionedController.addCallback(mUserSetupObserver);
        mUserSetupObserver.onUserSetupChanged();

        // disable profiling bars, since they overlap and clutter the output on app windows
        ThreadedRenderer.overrideProperty("disableProfileBars", "true");

        // Private API call to make the shadows look better for Recents
        ThreadedRenderer.overrideProperty("ambientRatio", String.valueOf(1.5f));
    }


    /**
     * When swiping up to dismiss the lock screen, the panel expansion fraction goes from 1f to 0f.
     * This results in the clock/notifications/other content disappearing off the top of the screen.
     *
     * We also use the expansion fraction to animate in the app/launcher surface from the bottom of
     * the screen, 'pushing' off the notifications and other content. To do this, we dispatch the
     * expansion fraction to the KeyguardViewMediator if we're in the process of dismissing the
     * keyguard.
     */
    private void dispatchPanelExpansionForKeyguardDismiss(float fraction, boolean trackingTouch) {
        // Things that mean we're not swiping to dismiss the keyguard, and should ignore this
        // expansion:
        // - Keyguard isn't even visible.
        // - Keyguard is occluded. Expansion changes here are the shade being expanded over the
        //   occluding activity.
        // - Keyguard is visible, but can't be dismissed (swiping up will show PIN/password prompt).
        // - The SIM is locked, you can't swipe to unlock. If the SIM is locked but there is no
        //   device lock set, canDismissLockScreen returns true even though you should not be able
        //   to dismiss the lock screen until entering the SIM PIN.
        // - QS is expanded and we're swiping - swiping up now will hide QS, not dismiss the
        //   keyguard.
        if (!isKeyguardShowing()
                || isOccluded()
                || !mKeyguardStateController.canDismissLockScreen()
                || mKeyguardViewMediator.isAnySimPinSecure()
                || (mNotificationPanelViewController.isQsExpanded() && trackingTouch)) {
            return;
        }

        // Otherwise, we should let the keyguard know about this if we're tracking touch, or if we
        // are already animating the keyguard dismiss (since we will need to either finish or cancel
        // the animation).
        if (trackingTouch
                || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe()
                || mKeyguardUnlockAnimationController.isUnlockingWithSmartSpaceTransition()) {
            mKeyguardStateController.notifyKeyguardDismissAmountChanged(
                    1f - fraction, trackingTouch);
        }
    }

    private void onPanelExpansionChanged(float fraction, boolean expanded, boolean tracking) {
        dispatchPanelExpansionForKeyguardDismiss(fraction, tracking);

        if (fraction == 0 || fraction == 1) {
            if (getNavigationBarView() != null) {
                getNavigationBarView().onStatusBarPanelStateChanged();
            }
            if (getNotificationPanelViewController() != null) {
                getNotificationPanelViewController().updateSystemUiStateFlags();
            }
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @VisibleForTesting
    protected void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, null, UserHandle.ALL);
    }

    protected QS createDefaultQSFragment() {
        return FragmentHostManager.get(mNotificationShadeWindowView).create(QSFragment.class);
    }

    private void setUpPresenter() {
        // Set up the initial notification state.
        mActivityLaunchAnimator.setCallback(mActivityLaunchAnimatorCallback);
        mActivityLaunchAnimator.addListener(mActivityLaunchAnimatorListener);
        mNotificationAnimationProvider = new NotificationLaunchAnimatorControllerProvider(
                mNotificationShadeWindowViewController,
                mNotifListContainer,
                mHeadsUpManager,
                mJankMonitor);
        mNotificationShelfController.setOnActivatedListener(mPresenter);
        mRemoteInputManager.addControllerCallback(mNotificationShadeWindowController);
        mStackScrollerController.setNotificationActivityStarter(mNotificationActivityStarter);
        mGutsManager.setNotificationActivityStarter(mNotificationActivityStarter);
        mNotificationsController.initialize(
                mPresenter,
                mNotifListContainer,
                mStackScrollerController.getNotifStackController(),
                mNotificationActivityStarter,
                mCentralSurfacesComponent.getBindRowCallback());
    }

    /**
     * Post-init task of {@link #start()}
     * @param state1 disable1 flags
     * @param state2 disable2 flags
     */
    protected void setUpDisableFlags(int state1, int state2) {
        mCommandQueue.disable(mDisplayId, state1, state2, false /* animate */);
    }

    /**
     * Ask the display to wake up if currently dozing, else do nothing
     *
     * @param time when to wake up
     * @param where the view requesting the wakeup
     * @param why the reason for the wake up
     */
    public void wakeUpIfDozing(long time, View where, String why) {
        if (mDozing && mScreenOffAnimationController.allowWakeUpIfDozing()) {
            mPowerManager.wakeUp(
                    time, PowerManager.WAKE_REASON_GESTURE, "com.android.systemui:" + why);
            mWakeUpComingFromTouch = true;
            where.getLocationInWindow(mTmpInt2);

            // NOTE, the incoming view can sometimes be the entire container... unsure if
            // this location is valuable enough
            mWakeUpTouchLocation = new PointF(mTmpInt2[0] + where.getWidth() / 2,
                    mTmpInt2[1] + where.getHeight() / 2);
            mFalsingCollector.onScreenOnFromTouch();
        }
    }

    // TODO(b/117478341): This was left such that CarStatusBar can override this method.
    // Try to remove this.
    protected void createNavigationBar(@Nullable RegisterStatusBarResult result) {
        mNavigationBarController.createNavigationBars(true /* includeDefaultDisplay */, result);
    }

    /**
     * Returns the {@link android.view.View.OnTouchListener} that will be invoked when the
     * background window of the status bar is clicked.
     */
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        return (v, event) -> {
            mAutoHideController.checkUserAutoHide(event);
            mRemoteInputManager.checkRemoteInputOutside(event);
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (mExpandedVisible) {
                    mShadeController.animateCollapsePanels();
                }
            }
            return mNotificationShadeWindowView.onTouchEvent(event);
        };
    }

    private void inflateStatusBarWindow() {
        if (mCentralSurfacesComponent != null) {
            // Tear down
            for (CentralSurfacesComponent.Startable s : mCentralSurfacesComponent.getStartables()) {
                s.stop();
            }
        }
        mCentralSurfacesComponent = mCentralSurfacesComponentFactory.create();
        mFragmentService.addFragmentInstantiationProvider(mCentralSurfacesComponent);

        mNotificationShadeWindowView = mCentralSurfacesComponent.getNotificationShadeWindowView();
        mNotificationShadeWindowViewController = mCentralSurfacesComponent
                .getNotificationShadeWindowViewController();
        mNotificationShadeWindowController.setNotificationShadeView(mNotificationShadeWindowView);
        mNotificationShadeWindowViewController.setupExpandedStatusBar();
        mNotificationPanelViewController =
                mCentralSurfacesComponent.getNotificationPanelViewController();
        mCentralSurfacesComponent.getLockIconViewController().init();
        mStackScrollerController =
                mCentralSurfacesComponent.getNotificationStackScrollLayoutController();
        mStackScroller = mStackScrollerController.getView();
        mNotifListContainer = mCentralSurfacesComponent.getNotificationListContainer();
        mPresenter = mCentralSurfacesComponent.getNotificationPresenter();
        mNotificationActivityStarter = mCentralSurfacesComponent.getNotificationActivityStarter();
        mNotificationShelfController = mCentralSurfacesComponent.getNotificationShelfController();
        mAuthRippleController = mCentralSurfacesComponent.getAuthRippleController();
        mAuthRippleController.init();

        mHeadsUpManager.addListener(mCentralSurfacesComponent.getStatusBarHeadsUpChangeListener());

        // Listen for demo mode changes
        mDemoModeController.addCallback(mDemoModeCallback);

        if (mCommandQueueCallbacks != null) {
            mCommandQueue.removeCallback(mCommandQueueCallbacks);
        }
        mCommandQueueCallbacks =
                mCentralSurfacesComponent.getCentralSurfacesCommandQueueCallbacks();
        // Connect in to the status bar manager service
        mCommandQueue.addCallback(mCommandQueueCallbacks);

        // Perform all other initialization for CentralSurfacesScope
        for (CentralSurfacesComponent.Startable s : mCentralSurfacesComponent.getStartables()) {
            s.start();
        }
    }

    protected void startKeyguard() {
        Trace.beginSection("CentralSurfaces#startKeyguard");
        mBiometricUnlockController = mBiometricUnlockControllerLazy.get();
        mBiometricUnlockController.setBiometricModeListener(
                new BiometricUnlockController.BiometricModeListener() {
                    @Override
                    public void onResetMode() {
                        setWakeAndUnlocking(false);
                    }

                    @Override
                    public void onModeChanged(int mode) {
                        switch (mode) {
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM:
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING:
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK:
                                setWakeAndUnlocking(true);
                        }
                    }

                    @Override
                    public void notifyBiometricAuthModeChanged() {
                        CentralSurfaces.this.notifyBiometricAuthModeChanged();
                    }

                    private void setWakeAndUnlocking(boolean wakeAndUnlocking) {
                        if (getNavigationBarView() != null) {
                            getNavigationBarView().setWakeAndUnlocking(wakeAndUnlocking);
                        }
                    }
                });
        mStatusBarKeyguardViewManager.registerCentralSurfaces(
                /* statusBar= */ this,
                mNotificationPanelViewController,
                mPanelExpansionStateManager,
                mBiometricUnlockController,
                mStackScroller,
                mKeyguardBypassController);
        mKeyguardStateController.addCallback(mKeyguardStateControllerCallback);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        mRemoteInputManager.addControllerCallback(mStatusBarKeyguardViewManager);
        mDynamicPrivacyController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);

        mLightBarController.setBiometricUnlockController(mBiometricUnlockController);
        mMediaManager.setBiometricUnlockController(mBiometricUnlockController);
        mKeyguardDismissUtil.setDismissHandler(this::executeWhenUnlocked);
        Trace.endSection();
    }

    public NotificationShadeWindowView getNotificationShadeWindowView() {
        return mNotificationShadeWindowView;
    }

    public NotificationShadeWindowViewController getNotificationShadeWindowViewController() {
        return mNotificationShadeWindowViewController;
    }

    public NotificationPanelViewController getNotificationPanelViewController() {
        return mNotificationPanelViewController;
    }

    public ViewGroup getBouncerContainer() {
        return mNotificationShadeWindowViewController.getBouncerContainer();
    }

    public int getStatusBarHeight() {
        return mStatusBarWindowController.getStatusBarHeight();
    }

    /**
     * Disable QS if device not provisioned.
     * If the user switcher is simple then disable QS during setup because
     * the user intends to use the lock screen user switcher, QS in not needed.
     */
    void updateQsExpansionEnabled() {
        final boolean expandEnabled = mDeviceProvisionedController.isDeviceProvisioned()
                && (mUserSetup || mUserSwitcherController == null
                        || !mUserSwitcherController.isSimpleUserSwitcher())
                && !isShadeDisabled()
                && ((mDisabled2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) == 0)
                && !mDozing
                && !ONLY_CORE_APPS;
        mNotificationPanelViewController.setQsExpansionEnabledPolicy(expandEnabled);
        Log.d(TAG, "updateQsExpansionEnabled - QS Expand enabled: " + expandEnabled);
    }

    public boolean isShadeDisabled() {
        return (mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0;
    }

    /**
     * Request a notification update
     * @param reason why we're requesting a notification update
     */
    public void requestNotificationUpdate(String reason) {
        mNotificationsController.requestNotificationUpdate(reason);
    }

    /**
     * Asks {@link KeyguardUpdateMonitor} to run face auth.
     */
    public void requestFaceAuth(boolean userInitiatedRequest) {
        if (!mKeyguardStateController.canDismissLockScreen()) {
            mKeyguardUpdateMonitor.requestFaceAuth(userInitiatedRequest);
        }
    }

    private void updateReportRejectedTouchVisibility() {
        if (mReportRejectedTouch == null) {
            return;
        }
        mReportRejectedTouch.setVisibility(mState == StatusBarState.KEYGUARD && !mDozing
                && mFalsingCollector.isReportingEnabled() ? View.VISIBLE : View.INVISIBLE);
    }

    boolean areNotificationAlertsDisabled() {
        return (mDisabled1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade,
            int flags) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, flags);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, false /* onlyProvisioned */, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade,
            @Nullable ActivityLaunchAnimator.Controller animationController,
            boolean showOverLockscreenWhenLocked) {
        startActivity(intent, dismissShade, animationController, showOverLockscreenWhenLocked,
                getActivityUserHandle(intent));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade,
            @Nullable ActivityLaunchAnimator.Controller animationController,
            boolean showOverLockscreenWhenLocked, UserHandle userHandle) {
        // Make sure that we dismiss the keyguard if it is directly dismissable or when we don't
        // want to show the activity above it.
        if (mKeyguardStateController.isUnlocked() || !showOverLockscreenWhenLocked) {
            startActivityDismissingKeyguard(intent, false, dismissShade,
                    false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */,
                    0 /* flags */, animationController, userHandle);
            return;
        }

        boolean animate =
                animationController != null && shouldAnimateLaunch(true /* isActivityIntent */,
                        showOverLockscreenWhenLocked);

        ActivityLaunchAnimator.Controller controller = null;
        if (animate) {
            // Wrap the animation controller to dismiss the shade and set
            // mIsLaunchingActivityOverLockscreen during the animation.
            ActivityLaunchAnimator.Controller delegate = wrapAnimationController(
                    animationController, dismissShade);
            controller = new DelegateLaunchAnimatorController(delegate) {
                @Override
                public void onIntentStarted(boolean willAnimate) {
                    getDelegate().onIntentStarted(willAnimate);

                    if (willAnimate) {
                        CentralSurfaces.this.mIsLaunchingActivityOverLockscreen = true;
                    }
                }

                @Override
                public void onLaunchAnimationEnd(boolean isExpandingFullyAbove) {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually finishing the
                    // animation so that we can assume that mIsLaunchingActivityOverLockscreen
                    // being true means that we will collapse the shade (or at least run the
                    // post collapse runnables) later on.
                    CentralSurfaces.this.mIsLaunchingActivityOverLockscreen = false;
                    getDelegate().onLaunchAnimationEnd(isExpandingFullyAbove);
                }

                @Override
                public void onLaunchAnimationCancelled() {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually finishing the
                    // animation so that we can assume that mIsLaunchingActivityOverLockscreen
                    // being true means that we will collapse the shade (or at least run the
                    // post collapse runnables) later on.
                    CentralSurfaces.this.mIsLaunchingActivityOverLockscreen = false;
                    getDelegate().onLaunchAnimationCancelled();
                }
            };
        } else if (dismissShade) {
            // The animation will take care of dismissing the shade at the end of the animation. If
            // we don't animate, collapse it directly.
            collapseShade();
        }

        mActivityLaunchAnimator.startIntentWithAnimation(controller, animate,
                intent.getPackage(), showOverLockscreenWhenLocked, (adapter) -> TaskStackBuilder
                        .create(mContext)
                        .addNextIntent(intent)
                        .startActivities(getActivityOptions(getDisplayId(), adapter),
                                userHandle));
    }

    /**
     * Whether we are currently animating an activity launch above the lockscreen (occluding
     * activity).
     */
    public boolean isLaunchingActivityOverLockscreen() {
        return mIsLaunchingActivityOverLockscreen;
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        startActivityDismissingKeyguard(intent, false, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, callback, 0,
                null /* animationController */, getActivityUserHandle(intent));
    }

    public void setQsExpanded(boolean expanded) {
        mNotificationShadeWindowController.setQsExpanded(expanded);
        mNotificationPanelViewController.setStatusAccessibilityImportance(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mNotificationPanelViewController.updateSystemUiStateFlags();
        if (getNavigationBarView() != null) {
            getNavigationBarView().onStatusBarPanelStateChanged();
        }
    }

    public boolean isWakeUpComingFromTouch() {
        return mWakeUpComingFromTouch;
    }

    public boolean isFalsingThresholdNeeded() {
        return true;
    }

    /**
     * To be called when there's a state change in StatusBarKeyguardViewManager.
     */
    public void onKeyguardViewManagerStatesUpdated() {
        logStateToEventlog();
    }

    public void setPanelExpanded(boolean isExpanded) {
        if (mPanelExpanded != isExpanded) {
            mNotificationLogger.onPanelExpandedChanged(isExpanded);
        }
        mPanelExpanded = isExpanded;
        mStatusBarHideIconsForBouncerManager.setPanelExpandedAndTriggerUpdate(isExpanded);
        mNotificationShadeWindowController.setPanelExpanded(isExpanded);
        mStatusBarStateController.setPanelExpanded(isExpanded);
        if (isExpanded && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from Height");
            }
            clearNotificationEffects();
        }

        if (!isExpanded) {
            mRemoteInputManager.onPanelCollapsed();
        }
    }

    public ViewGroup getNotificationScrollLayout() {
        return mStackScroller;
    }

    public boolean isPulsing() {
        return mDozeServiceHost.isPulsing();
    }

    @Nullable
    public View getAmbientIndicationContainer() {
        return mAmbientIndicationContainer;
    }

    /**
     * When the keyguard is showing and covered by a "showWhenLocked" activity it
     * is occluded. This is controlled by {@link com.android.server.policy.PhoneWindowManager}
     *
     * @return whether the keyguard is currently occluded
     */
    public boolean isOccluded() {
        return mKeyguardStateController.isOccluded();
    }

    /** A launch animation was cancelled. */
    //TODO: These can / should probably be moved to NotificationPresenter or ShadeController
    public void onLaunchAnimationCancelled(boolean isLaunchForActivity) {
        if (mPresenter.isPresenterFullyCollapsed() && !mPresenter.isCollapsing()
                && isLaunchForActivity) {
            onClosingFinished();
        } else {
            mShadeController.collapsePanel(true /* animate */);
        }
    }

    /** A launch animation ended. */
    public void onLaunchAnimationEnd(boolean launchIsFullScreen) {
        if (!mPresenter.isCollapsing()) {
            onClosingFinished();
        }
        if (launchIsFullScreen) {
            instantCollapseNotificationPanel();
        }
    }

    /**
     * Whether we should animate an activity launch.
     *
     * Note: This method must be called *before* dismissing the keyguard.
     */
    public boolean shouldAnimateLaunch(boolean isActivityIntent, boolean showOverLockscreen) {
        // TODO(b/184121838): Support launch animations when occluded.
        if (isOccluded()) {
            return false;
        }

        // Always animate if we are not showing the keyguard or if we animate over the lockscreen
        // (without unlocking it).
        if (showOverLockscreen || !mKeyguardStateController.isShowing()) {
            return true;
        }

        // If we are locked and have to dismiss the keyguard, only animate if remote unlock
        // animations are enabled. We also don't animate non-activity launches as they can break the
        // animation.
        // TODO(b/184121838): Support non activity launches on the lockscreen.
        return isActivityIntent && KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation;
    }

    /** Whether we should animate an activity launch. */
    public boolean shouldAnimateLaunch(boolean isActivityIntent) {
        return shouldAnimateLaunch(isActivityIntent, false /* showOverLockscreen */);
    }

    public boolean isDeviceInVrMode() {
        return mPresenter.isDeviceInVrMode();
    }

    public NotificationPresenter getPresenter() {
        return mPresenter;
    }

    @VisibleForTesting
    void setBarStateForTest(int state) {
        mState = state;
    }

    static class KeyboardShortcutsMessage {
        final int mDeviceId;

        KeyboardShortcutsMessage(int deviceId) {
            mDeviceId = deviceId;
        }
    }

    static class AnimateExpandSettingsPanelMessage {
        final String mSubpanel;

        AnimateExpandSettingsPanelMessage(String subpanel) {
            mSubpanel = subpanel;
        }
    }

    private void maybeEscalateHeadsUp() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            final StatusBarNotification sbn = entry.getSbn();
            final Notification notification = sbn.getNotification();
            if (notification.fullScreenIntent != null) {
                if (DEBUG) {
                    Log.d(TAG, "converting a heads up to fullScreen");
                }
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_HEADS_UP_ESCALATION,
                            sbn.getKey());
                    wakeUpForFullScreenIntent();
                    notification.fullScreenIntent.send();
                    entry.notifyFullScreenIntentLaunched();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        });
        mHeadsUpManager.releaseAllImmediately();
    }

    void wakeUpForFullScreenIntent() {
        if (isGoingToSleep() || mDozing) {
            mPowerManager.wakeUp(
                    SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION,
                    "com.android.systemui:full_screen_intent");
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
        }
    }

    void makeExpandedVisible(boolean force) {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (!force && (mExpandedVisible || !mCommandQueue.panelsEnabled())) {
            return;
        }

        mExpandedVisible = true;

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        mNotificationShadeWindowController.setPanelVisible(true);

        visibilityChanged(true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, !force /* animate */);
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
    }

    public void postAnimateCollapsePanels() {
        mMainExecutor.execute(mShadeController::animateCollapsePanels);
    }

    public void postAnimateForceCollapsePanels() {
        mMainExecutor.execute(
                () -> mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE,
                true /* force */));
    }

    public void postAnimateOpenPanels() {
        mMessageRouter.sendMessage(MSG_OPEN_SETTINGS_PANEL);
    }

    public boolean isExpandedVisible() {
        return mExpandedVisible;
    }

    public boolean isPanelExpanded() {
        return mPanelExpanded;
    }

    /**
     * Called when another window is about to transfer it's input focus.
     */
    public void onInputFocusTransfer(boolean start, boolean cancel, float velocity) {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        if (start) {
            mNotificationPanelViewController.startWaitingForOpenPanelGesture();
        } else {
            mNotificationPanelViewController.stopWaitingForOpenPanelGesture(cancel, velocity);
        }
    }

    public void animateCollapseQuickSettings() {
        if (mState == StatusBarState.SHADE) {
            mNotificationPanelViewController.collapsePanel(
                    true, false /* delayed */, 1.0f /* speedUpFactor */);
        }
    }

    void makeExpandedInvisible() {
        if (SPEW) Log.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible || mNotificationShadeWindowView == null) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mNotificationPanelViewController.collapsePanel(/*animate=*/ false, false /* delayed*/,
                1.0f /* speedUpFactor */);

        mNotificationPanelViewController.closeQs();

        mExpandedVisible = false;
        visibilityChanged(false);

        // Update the visibility of notification shade and status bar window.
        mNotificationShadeWindowController.setPanelVisible(false);
        mStatusBarWindowController.setForceStatusBarVisible(false);

        // Close any guts that might be visible
        mGutsManager.closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                true /* removeControls */, -1 /* x */, -1 /* y */, true /* resetMenu */);

        mShadeController.runPostCollapseRunnables();
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        if (!mNotificationActivityStarter.isCollapsingToShowActivityOverLockscreen()) {
            showBouncerOrLockScreenIfKeyguard();
        } else if (DEBUG) {
            Log.d(TAG, "Not showing bouncer due to activity showing over lockscreen");
        }
        mCommandQueue.recomputeDisableFlags(
                mDisplayId,
                mNotificationPanelViewController.hideStatusBarIconsWhenExpanded() /* animate */);

        // Trimming will happen later if Keyguard is showing - doing it here might cause a jank in
        // the bouncer appear animation.
        if (!mStatusBarKeyguardViewManager.isShowing()) {
            WindowManagerGlobal.getInstance().trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        }
    }

    /** Called when a touch event occurred on {@link PhoneStatusBarView}. */
    public void onTouchEvent(MotionEvent event) {
        // TODO(b/202981994): Move this touch debugging to a central location. (Right now, it's
        //   split between NotificationPanelViewController and here.)
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_STATUSBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        mDisabled1, mDisabled2);
            }

        }

        if (SPEW) {
            Log.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled1="
                    + mDisabled1 + " mDisabled2=" + mDisabled2);
        } else if (CHATTY) {
            if (event.getAction() != MotionEvent.ACTION_MOVE) {
                Log.d(TAG, String.format(
                            "panel: %s at (%f, %f) mDisabled1=0x%08x mDisabled2=0x%08x",
                            MotionEvent.actionToString(event.getAction()),
                            event.getRawX(), event.getRawY(), mDisabled1, mDisabled2));
            }
        }

        if (DEBUG_GESTURES) {
            mGestureRec.add(event);
        }

        if (mStatusBarWindowState == WINDOW_STATE_SHOWING) {
            final boolean upOrCancel =
                    event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL;
            setInteracting(StatusBarManager.WINDOW_STATUS_BAR, !upOrCancel || mExpandedVisible);
        }
    }

    public GestureRecorder getGestureRecorder() {
        return mGestureRec;
    }

    public BiometricUnlockController getBiometricUnlockController() {
        return mBiometricUnlockController;
    }

    void showTransientUnchecked() {
        if (!mTransientShown) {
            mTransientShown = true;
            mNoAnimationOnNextBarModeChange = true;
            maybeUpdateBarMode();
        }
    }


    void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            maybeUpdateBarMode();
        }
    }

    private void maybeUpdateBarMode() {
        final int barMode = barMode(mTransientShown, mAppearance);
        if (updateBarMode(barMode)) {
            mLightBarController.onStatusBarModeChanged(barMode);
            updateBubblesVisibility();
        }
    }

    private boolean updateBarMode(int barMode) {
        if (mStatusBarMode != barMode) {
            mStatusBarMode = barMode;
            checkBarModes();
            mAutoHideController.touchAutoHide();
            return true;
        }
        return false;
    }

    private @TransitionMode int barMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_STATUS_BARS;
        if (mOngoingCallController.hasOngoingCall() && mIsFullscreen) {
            return MODE_SEMI_TRANSPARENT;
        } else if (isTransient) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
            return MODE_LIGHTS_OUT;
        } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((appearance & APPEARANCE_OPAQUE_STATUS_BARS) != 0) {
            return MODE_OPAQUE;
        } else if ((appearance & APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS) != 0) {
            return MODE_SEMI_TRANSPARENT;
        } else {
            return MODE_TRANSPARENT;
        }
    }

    protected void showWirelessChargingAnimation(int batteryLevel) {
        showChargingAnimation(batteryLevel, UNKNOWN_BATTERY_LEVEL, 0);
    }

    protected void showChargingAnimation(int batteryLevel, int transmittingBatteryLevel,
            long animationDelay) {
        WirelessChargingAnimation.makeWirelessChargingAnimation(mContext, null,
                transmittingBatteryLevel, batteryLevel,
                new WirelessChargingAnimation.Callback() {
                    @Override
                    public void onAnimationStarting() {
                        mNotificationShadeWindowController.setRequestTopUi(true, TAG);
                    }

                    @Override
                    public void onAnimationEnded() {
                        mNotificationShadeWindowController.setRequestTopUi(false, TAG);
                    }
                }, false, sUiEventLogger).show(animationDelay);
    }

    public void checkBarModes() {
        if (mDemoModeController.isInDemoMode()) return;
        if (mStatusBarTransitions != null) {
            checkBarMode(mStatusBarMode, mStatusBarWindowState, mStatusBarTransitions);
        }
        mNavigationBarController.checkNavBarModes(mDisplayId);
        mNoAnimationOnNextBarModeChange = false;
    }

    // Called by NavigationBarFragment
    public void setQsScrimEnabled(boolean scrimEnabled) {
        mNotificationPanelViewController.setQsScrimEnabled(scrimEnabled);
    }

    /** Temporarily hides Bubbles if the status bar is hidden. */
    void updateBubblesVisibility() {
        mBubblesOptional.ifPresent(bubbles -> bubbles.onStatusBarVisibilityChanged(
                mStatusBarMode != MODE_LIGHTS_OUT
                        && mStatusBarMode != MODE_LIGHTS_OUT_TRANSPARENT
                        && !mStatusBarWindowHidden));
    }

    void checkBarMode(@TransitionMode int mode, @WindowVisibleState int windowState,
            BarTransitions transitions) {
        final boolean anim = !mNoAnimationOnNextBarModeChange && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN;
        transitions.transitionTo(mode, anim);
    }

    private void finishBarAnimations() {
        if (mStatusBarTransitions != null) {
            mStatusBarTransitions.finishAnimations();
        }
        mNavigationBarController.finishBarAnimations(mDisplayId);
    }

    private final Runnable mCheckBarModes = this::checkBarModes;

    public void setInteracting(int barWindow, boolean interacting) {
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
        checkBarModes();
    }

    private void dismissVolumeDialog() {
        if (mVolumeComponent != null) {
            mVolumeComponent.dismissNow();
        }
    }

    public static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mExpandedVisible);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }

        pw.print("  mInteractingWindows="); pw.println(mInteractingWindows);
        pw.print("  mStatusBarWindowState=");
        pw.println(windowStateToString(mStatusBarWindowState));
        pw.print("  mStatusBarMode=");
        pw.println(BarTransitions.modeToString(mStatusBarMode));
        pw.print("  mDozing="); pw.println(mDozing);
        pw.print("  mWallpaperSupported= "); pw.println(mWallpaperSupported);

        pw.println("  ShadeWindowView: ");
        if (mNotificationShadeWindowViewController != null) {
            mNotificationShadeWindowViewController.dump(pw, args);
            dumpBarTransitions(pw, "PhoneStatusBarTransitions", mStatusBarTransitions);
        }

        pw.println("  mMediaManager: ");
        if (mMediaManager != null) {
            mMediaManager.dump(pw, args);
        }

        pw.println("  Panels: ");
        if (mNotificationPanelViewController != null) {
            pw.println("    mNotificationPanel="
                    + mNotificationPanelViewController.getView() + " params="
                    + mNotificationPanelViewController.getView().getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanelViewController.dump(pw, args);
        }
        pw.println("  mStackScroller: ");
        if (mStackScroller != null) {
            // Double indent until we rewrite the rest of this dump()
            pw.increaseIndent();
            pw.increaseIndent();
            mStackScroller.dump(pw, args);
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
        pw.println("  Theme:");
        String nightMode = mUiModeManager == null ? "null" : mUiModeManager.getNightMode() + "";
        pw.println("    dark theme: " + nightMode +
                " (auto: " + UiModeManager.MODE_NIGHT_AUTO +
                ", yes: " + UiModeManager.MODE_NIGHT_YES +
                ", no: " + UiModeManager.MODE_NIGHT_NO + ")");
        final boolean lightWpTheme = mContext.getThemeResId()
                == R.style.Theme_SystemUI_LightWallpaper;
        pw.println("    light wallpaper theme: " + lightWpTheme);

        if (mKeyguardIndicationController != null) {
            mKeyguardIndicationController.dump(pw, args);
        }

        if (mScrimController != null) {
            mScrimController.dump(pw, args);
        }

        if (mLightRevealScrim != null) {
            pw.println(
                    "mLightRevealScrim.getRevealEffect(): " + mLightRevealScrim.getRevealEffect());
            pw.println(
                    "mLightRevealScrim.getRevealAmount(): " + mLightRevealScrim.getRevealAmount());
        }

        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.dump(pw);
        }

        mNotificationsController.dump(pw, args, DUMPTRUCK);

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(pw, args);
        }

        if (mHeadsUpManager != null) {
            mHeadsUpManager.dump(pw, args);
        } else {
            pw.println("  mHeadsUpManager: null");
        }

        if (mStatusBarTouchableRegionManager != null) {
            mStatusBarTouchableRegionManager.dump(pw, args);
        } else {
            pw.println("  mStatusBarTouchableRegionManager: null");
        }

        if (mLightBarController != null) {
            mLightBarController.dump(pw, args);
        }

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }

        pw.println("Camera gesture intents:");
        pw.println("   Insecure camera: " + CameraIntents.getInsecureCameraIntent(mContext));
        pw.println("   Secure camera: " + CameraIntents.getSecureCameraIntent(mContext));
        pw.println("   Override package: "
                + CameraIntents.getOverrideCameraPackage(mContext));
    }

    public static void dumpBarTransitions(
            PrintWriter pw, String var, @Nullable BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        if (transitions != null) {
            pw.println(BarTransitions.modeToString(transitions.getMode()));
        } else {
            pw.println("Unknown");
        }
    }

    public void createAndAddWindows(@Nullable RegisterStatusBarResult result) {
        makeStatusBarView(result);
        mNotificationShadeWindowController.attach();
        mStatusBarWindowController.attach();
    }

    // called by makeStatusbar and also by PhoneStatusBarView
    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
        mDisplay.getSize(mCurrentDisplaySize);
        if (DEBUG_GESTURES) {
            mGestureRec.tag("display",
                    String.format("%dx%d", mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        }
    }

    float getDisplayDensity() {
        return mDisplayMetrics.density;
    }

    public float getDisplayWidth() {
        return mDisplayMetrics.widthPixels;
    }

    public float getDisplayHeight() {
        return mDisplayMetrics.heightPixels;
    }

    int getRotation() {
        return mDisplay.getRotation();
    }

    int getDisplayId() {
        return mDisplayId;
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade, int flags) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */,
                flags, null /* animationController */, getActivityUserHandle(intent));
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, 0);
    }

    void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            final boolean dismissShade, final boolean disallowEnterPictureInPictureWhileLaunching,
            final Callback callback, int flags,
            @Nullable ActivityLaunchAnimator.Controller animationController,
            final UserHandle userHandle) {
        if (onlyProvisioned && !mDeviceProvisionedController.isDeviceProvisioned()) return;

        final boolean willLaunchResolverActivity =
                mActivityIntentHelper.wouldLaunchResolverActivity(intent,
                        mLockscreenUserManager.getCurrentUserId());

        boolean animate =
                animationController != null && !willLaunchResolverActivity && shouldAnimateLaunch(
                        true /* isActivityIntent */);
        ActivityLaunchAnimator.Controller animController =
                animationController != null ? wrapAnimationController(animationController,
                        dismissShade) : null;

        // If we animate, we will dismiss the shade only once the animation is done. This is taken
        // care of by the StatusBarLaunchAnimationController.
        boolean dismissShadeDirectly = dismissShade && animController == null;

        Runnable runnable = () -> {
            mAssistManagerLazy.get().hideAssist();
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(flags);
            int[] result = new int[]{ActivityManager.START_CANCELED};

            mActivityLaunchAnimator.startIntentWithAnimation(animController,
                    animate, intent.getPackage(), (adapter) -> {
                        ActivityOptions options = new ActivityOptions(
                                getActivityOptions(mDisplayId, adapter));
                        options.setDisallowEnterPictureInPictureWhileLaunching(
                                disallowEnterPictureInPictureWhileLaunching);
                        if (CameraIntents.isInsecureCameraIntent(intent)) {
                            // Normally an activity will set it's requested rotation
                            // animation on its window. However when launching an activity
                            // causes the orientation to change this is too late. In these cases
                            // the default animation is used. This doesn't look good for
                            // the camera (as it rotates the camera contents out of sync
                            // with physical reality). So, we ask the WindowManager to
                            // force the crossfade animation if an orientation change
                            // happens to occur during the launch.
                            options.setRotationAnimationHint(
                                    WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS);
                        }
                        if (Settings.Panel.ACTION_VOLUME.equals(intent.getAction())) {
                            // Settings Panel is implemented as activity(not a dialog), so
                            // underlying app is paused and may enter picture-in-picture mode
                            // as a result.
                            // So we need to disable picture-in-picture mode here
                            // if it is volume panel.
                            options.setDisallowEnterPictureInPictureWhileLaunching(true);
                        }

                        try {
                            result[0] = ActivityTaskManager.getService().startActivityAsUser(
                                    null, mContext.getBasePackageName(),
                                    mContext.getAttributionTag(),
                                    intent,
                                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                    null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null,
                                    options.toBundle(), userHandle.getIdentifier());
                        } catch (RemoteException e) {
                            Log.w(TAG, "Unable to start activity", e);
                        }
                        return result[0];
                    });

            if (callback != null) {
                callback.onActivityStarted(result[0]);
            }
        };
        Runnable cancelRunnable = () -> {
            if (callback != null) {
                callback.onActivityStarted(ActivityManager.START_CANCELED);
            }
        };
        executeRunnableDismissingKeyguard(runnable, cancelRunnable, dismissShadeDirectly,
                willLaunchResolverActivity, true /* deferred */, animate);
    }

    @Nullable
    private ActivityLaunchAnimator.Controller wrapAnimationController(
            ActivityLaunchAnimator.Controller animationController, boolean dismissShade) {
        View rootView = animationController.getLaunchContainer().getRootView();

        Optional<ActivityLaunchAnimator.Controller> controllerFromStatusBar =
                mStatusBarWindowController.wrapAnimationControllerIfInStatusBar(
                        rootView, animationController);
        if (controllerFromStatusBar.isPresent()) {
            return controllerFromStatusBar.get();
        }

        if (dismissShade) {
            // If the view is not in the status bar, then we are animating a view in the shade.
            // We have to make sure that we collapse it when the animation ends or is cancelled.
            return new StatusBarLaunchAnimatorController(animationController, this,
                    true /* isLaunchForActivity */);
        }

        return animationController;
    }

    public void readyForKeyguardDone() {
        mStatusBarKeyguardViewManager.readyForKeyguardDone();
    }

    public void executeRunnableDismissingKeyguard(final Runnable runnable,
            final Runnable cancelAction,
            final boolean dismissShade,
            final boolean afterKeyguardGone,
            final boolean deferred) {
        executeRunnableDismissingKeyguard(runnable, cancelAction, dismissShade, afterKeyguardGone,
                deferred, false /* willAnimateOnKeyguard */);
    }

    public void executeRunnableDismissingKeyguard(final Runnable runnable,
            final Runnable cancelAction,
            final boolean dismissShade,
            final boolean afterKeyguardGone,
            final boolean deferred,
            final boolean willAnimateOnKeyguard) {
        OnDismissAction onDismissAction = new OnDismissAction() {
            @Override
            public boolean onDismiss() {
                if (runnable != null) {
                    if (mStatusBarKeyguardViewManager.isShowing()
                            && mStatusBarKeyguardViewManager.isOccluded()) {
                        mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
                    } else {
                        mMainExecutor.execute(runnable);
                    }
                }
                if (dismissShade) {
                    if (mExpandedVisible && !mBouncerShowing) {
                        mShadeController.animateCollapsePanels(
                                CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                                true /* force */, true /* delayed*/);
                    } else {

                        // Do it after DismissAction has been processed to conserve the needed
                        // ordering.
                        mMainExecutor.execute(mShadeController::runPostCollapseRunnables);
                    }
                } else if (CentralSurfaces.this.isInLaunchTransition()
                        && mNotificationPanelViewController.isLaunchTransitionFinished()) {

                    // We are not dismissing the shade, but the launch transition is already
                    // finished,
                    // so nobody will call readyForKeyguardDone anymore. Post it such that
                    // keyguardDonePending gets called first.
                    mMainExecutor.execute(mStatusBarKeyguardViewManager::readyForKeyguardDone);
                }
                return deferred;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return willAnimateOnKeyguard;
            }
        };
        dismissKeyguardThenExecute(onDismissAction, cancelAction, afterKeyguardGone);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Trace.beginSection("CentralSurfaces#onReceive");
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                KeyboardShortcuts.dismiss();
                mRemoteInputManager.closeRemoteInputs();
                if (mLockscreenUserManager.isCurrentProfile(getSendingUserId())) {
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    if (reason != null) {
                        if (reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                            flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                        }
                        // Do not collapse notifications when starting dreaming if the notifications
                        // shade is used for the screen off animation. It might require expanded
                        // state for the scrims to be visible
                        if (reason.equals(SYSTEM_DIALOG_REASON_DREAM)
                                && mScreenOffAnimationController.shouldExpandNotifications()) {
                            flags |= CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL;
                        }
                    }
                    mShadeController.animateCollapsePanels(flags);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mNotificationShadeWindowController != null) {
                    mNotificationShadeWindowController.setNotTouchable(false);
                }
                finishBarAnimations();
                resetUserExpandedStates();
            }
            Trace.endSection();
        }
    };

    private final BroadcastReceiver mDemoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (ACTION_FAKE_ARTWORK.equals(action)) {
                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    mPresenter.updateMediaMetaData(true, true);
                }
            }
        }
    };

    public void resetUserExpandedStates() {
        mNotificationsController.resetUserExpandedStates();
    }

    private void executeWhenUnlocked(OnDismissAction action, boolean requiresShadeOpen,
            boolean afterKeyguardGone) {
        if (mStatusBarKeyguardViewManager.isShowing() && requiresShadeOpen) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        }
        dismissKeyguardThenExecute(action, null /* cancelAction */,
                afterKeyguardGone /* afterKeyguardGone */);
    }

    protected void dismissKeyguardThenExecute(OnDismissAction action, boolean afterKeyguardGone) {
        dismissKeyguardThenExecute(action, null /* cancelRunnable */, afterKeyguardGone);
    }

    @Override
    public void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancelAction,
            boolean afterKeyguardGone) {
        if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP
                && mKeyguardStateController.canDismissLockScreen()
                && !mStatusBarStateController.leaveOpenOnKeyguardHide()
                && mDozeServiceHost.isPulsing()) {
            // Reuse the biometric wake-and-unlock transition if we dismiss keyguard from a pulse.
            // TODO: Factor this transition out of BiometricUnlockController.
            mBiometricUnlockController.startWakeAndUnlock(
                    BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
        }
        if (mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                    afterKeyguardGone);
        } else {
            action.onDismiss();
        }
    }
    /**
     * Notify the shade controller that the current user changed
     *
     * @param newUserId userId of the new user
     */
    public void setLockscreenUser(int newUserId) {
        if (mLockscreenWallpaper != null) {
            mLockscreenWallpaper.setCurrentUser(newUserId);
        }
        mScrimController.setCurrentUser(newUserId);
        if (mWallpaperSupported) {
            mWallpaperChangedReceiver.onReceive(mContext, null);
        }
    }

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        // Update the quick setting tiles
        if (mQSPanelController != null) {
            mQSPanelController.updateResources();
        }

        if (mStatusBarWindowController != null) {
            mStatusBarWindowController.refreshStatusBarHeight();
        }

        if (mNotificationPanelViewController != null) {
            mNotificationPanelViewController.updateResources();
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.updateResources();
        }
        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.updateResources();
        }

        mPowerButtonReveal = new PowerButtonReveal(mContext.getResources().getDimensionPixelSize(
                com.android.systemui.R.dimen.physical_power_button_center_screen_location_y));
    }

    // Visibility reporting
    protected void handleVisibleToUserChanged(boolean visibleToUser) {
        if (visibleToUser) {
            handleVisibleToUserChangedImpl(visibleToUser);
            mNotificationLogger.startNotificationLogging();
        } else {
            mNotificationLogger.stopNotificationLogging();
            handleVisibleToUserChangedImpl(visibleToUser);
        }
    }

    // Visibility reporting
    void handleVisibleToUserChangedImpl(boolean visibleToUser) {
        if (visibleToUser) {
            /* The LEDs are turned off when the notification panel is shown, even just a little bit.
             * See also CentralSurfaces.setPanelExpanded for another place where we attempt to do
             * this.
             */
            boolean pinnedHeadsUp = mHeadsUpManager.hasPinnedHeadsUp();
            boolean clearNotificationEffects =
                    !mPresenter.isPresenterFullyCollapsed() &&
                            (mState == StatusBarState.SHADE
                                    || mState == StatusBarState.SHADE_LOCKED);
            int notificationLoad = mNotificationsController.getActiveNotificationsCount();
            if (pinnedHeadsUp && mPresenter.isPresenterFullyCollapsed()) {
                notificationLoad = 1;
            }
            final int finalNotificationLoad = notificationLoad;
            mUiBgExecutor.execute(() -> {
                try {
                    mBarService.onPanelRevealed(clearNotificationEffects,
                            finalNotificationLoad);
                } catch (RemoteException ex) {
                    // Won't fail unless the world has ended.
                }
            });
        } else {
            mUiBgExecutor.execute(() -> {
                try {
                    mBarService.onPanelHidden();
                } catch (RemoteException ex) {
                    // Won't fail unless the world has ended.
                }
            });
        }

    }

    private void logStateToEventlog() {
        boolean isShowing = mStatusBarKeyguardViewManager.isShowing();
        boolean isOccluded = mStatusBarKeyguardViewManager.isOccluded();
        boolean isBouncerShowing = mStatusBarKeyguardViewManager.isBouncerShowing();
        boolean isSecure = mKeyguardStateController.isMethodSecure();
        boolean unlocked = mKeyguardStateController.canDismissLockScreen();
        int stateFingerprint = getLoggingFingerprint(mState,
                isShowing,
                isOccluded,
                isBouncerShowing,
                isSecure,
                unlocked);
        if (stateFingerprint != mLastLoggedStateFingerprint) {
            if (mStatusBarStateLog == null) {
                mStatusBarStateLog = new LogMaker(MetricsEvent.VIEW_UNKNOWN);
            }
            mMetricsLogger.write(mStatusBarStateLog
                    .setCategory(isBouncerShowing ? MetricsEvent.BOUNCER : MetricsEvent.LOCKSCREEN)
                    .setType(isShowing ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                    .setSubtype(isSecure ? 1 : 0));
            EventLogTags.writeSysuiStatusBarState(mState,
                    isShowing ? 1 : 0,
                    isOccluded ? 1 : 0,
                    isBouncerShowing ? 1 : 0,
                    isSecure ? 1 : 0,
                    unlocked ? 1 : 0);
            mLastLoggedStateFingerprint = stateFingerprint;

            StringBuilder uiEventValueBuilder = new StringBuilder();
            uiEventValueBuilder.append(isBouncerShowing ? "BOUNCER" : "LOCKSCREEN");
            uiEventValueBuilder.append(isShowing ? "_OPEN" : "_CLOSE");
            uiEventValueBuilder.append(isSecure ? "_SECURE" : "_INSECURE");
            sUiEventLogger.log(StatusBarUiEvent.valueOf(uiEventValueBuilder.toString()));
        }
    }

    /**
     * Returns a fingerprint of fields logged to eventlog
     */
    private static int getLoggingFingerprint(int statusBarState, boolean keyguardShowing,
            boolean keyguardOccluded, boolean bouncerShowing, boolean secure,
            boolean currentlyInsecure) {
        // Reserve 8 bits for statusBarState. We'll never go higher than
        // that, right? Riiiight.
        return (statusBarState & 0xFF)
                | ((keyguardShowing   ? 1 : 0) <<  8)
                | ((keyguardOccluded  ? 1 : 0) <<  9)
                | ((bouncerShowing    ? 1 : 0) << 10)
                | ((secure            ? 1 : 0) << 11)
                | ((currentlyInsecure ? 1 : 0) << 12);
    }

    @Override
    public void postQSRunnableDismissingKeyguard(final Runnable runnable) {
        mMainExecutor.execute(() -> {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
            executeRunnableDismissingKeyguard(
                    () -> mMainExecutor.execute(runnable), null, false, false, false);
        });
    }

    @Override
    public void postStartActivityDismissingKeyguard(PendingIntent intent) {
        postStartActivityDismissingKeyguard(intent, null /* animationController */);
    }

    @Override
    public void postStartActivityDismissingKeyguard(final PendingIntent intent,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mMainExecutor.execute(() -> startPendingIntentDismissingKeyguard(intent,
                null /* intentSentUiThreadCallback */, animationController));
    }

    @Override
    public void postStartActivityDismissingKeyguard(final Intent intent, int delay) {
        postStartActivityDismissingKeyguard(intent, delay, null /* animationController */);
    }

    @Override
    public void postStartActivityDismissingKeyguard(Intent intent, int delay,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mMainExecutor.executeDelayed(
                () ->
                        startActivityDismissingKeyguard(intent, true /* onlyProvisioned */,
                                true /* dismissShade */,
                                false /* disallowEnterPictureInPictureWhileLaunching */,
                                null /* callback */,
                                0 /* flags */,
                                animationController,
                                getActivityUserHandle(intent)),
                delay);
    }

    public void showKeyguard() {
        mStatusBarStateController.setKeyguardRequested(true);
        mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
        updateIsKeyguard();
        mAssistManagerLazy.get().onLockscreenShown();
    }

    public boolean hideKeyguard() {
        mStatusBarStateController.setKeyguardRequested(false);
        return updateIsKeyguard();
    }

    boolean updateIsKeyguard() {
        return updateIsKeyguard(false /* forceStateChange */);
    }

    boolean updateIsKeyguard(boolean forceStateChange) {
        boolean wakeAndUnlocking = mBiometricUnlockController.isWakeAndUnlock();

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardForDozing = mDozeServiceHost.getDozingRequested()
                && (!mDeviceInteractive || (isGoingToSleep()
                    && (isScreenFullyOff()
                        || (mKeyguardStateController.isShowing() && !isOccluded()))));
        boolean isWakingAndOccluded = isOccluded() && isWakingOrAwake();
        boolean shouldBeKeyguard = (mStatusBarStateController.isKeyguardRequested()
                || keyguardForDozing) && !wakeAndUnlocking && !isWakingAndOccluded;
        if (keyguardForDozing) {
            updatePanelExpansionForKeyguard();
        }
        if (shouldBeKeyguard) {
            if (mScreenOffAnimationController.isKeyguardShowDelayed()
                    || (isGoingToSleep()
                    && mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_OFF)) {
                // Delay showing the keyguard until screen turned off.
            } else {
                showKeyguardImpl();
            }
        } else {
            // During folding a foldable device this might be called as a result of
            // 'onScreenTurnedOff' call for the inner display.
            // In this case:
            //  * When phone is locked on folding: it doesn't make sense to hide keyguard as it
            //    will be immediately locked again
            //  * When phone is unlocked: we still don't want to execute hiding of the keyguard
            //    as the animation could prepare 'fake AOD' interface (without actually
            //    transitioning to keyguard state) and this might reset the view states
            if (!mScreenOffAnimationController.isKeyguardHideDelayed()) {
                return hideKeyguardImpl(forceStateChange);
            }
        }
        return false;
    }

    public void showKeyguardImpl() {
        Trace.beginSection("CentralSurfaces#showKeyguard");
        // In case we're locking while a smartspace transition is in progress, reset it.
        mKeyguardUnlockAnimationController.resetSmartspaceTransition();
        if (mKeyguardStateController.isLaunchTransitionFadingAway()) {
            mNotificationPanelViewController.cancelAnimation();
            onLaunchTransitionFadingEnded();
        }
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (!mLockscreenShadeTransitionController.isWakingToShadeLocked()) {
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
        updatePanelExpansionForKeyguard();
        Trace.endSection();
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mBiometricUnlockController.getMode()
                != BiometricUnlockController.MODE_WAKE_AND_UNLOCK && !mBouncerShowing) {
            mShadeController.instantExpandNotificationsPanel();
        }
    }

    private void onLaunchTransitionFadingEnded() {
        mNotificationPanelViewController.resetAlpha();
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        runLaunchTransitionEndRunnable();
        mKeyguardStateController.setLaunchTransitionFadingAway(false);
        mPresenter.updateMediaMetaData(true /* metaDataChanged */, true);
    }

    public boolean isInLaunchTransition() {
        return mNotificationPanelViewController.isLaunchTransitionRunning()
                || mNotificationPanelViewController.isLaunchTransitionFinished();
    }

    /**
     * Fades the content of the keyguard away after the launch transition is done.
     *
     * @param beforeFading the runnable to be run when the circle is fully expanded and the fading
     *                     starts
     * @param endRunnable the runnable to be run when the transition is done. Will not run
     *                    if the transition is cancelled, instead cancelRunnable will run
     * @param cancelRunnable the runnable to be run if the transition is cancelled
     */
    public void fadeKeyguardAfterLaunchTransition(final Runnable beforeFading,
            Runnable endRunnable, Runnable cancelRunnable) {
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        mLaunchTransitionEndRunnable = endRunnable;
        mLaunchTransitionCancelRunnable = cancelRunnable;
        Runnable hideRunnable = () -> {
            mKeyguardStateController.setLaunchTransitionFadingAway(true);
            if (beforeFading != null) {
                beforeFading.run();
            }
            updateScrimController();
            mPresenter.updateMediaMetaData(false, true);
            mNotificationPanelViewController.resetAlpha();
            mNotificationPanelViewController.fadeOut(
                    FADE_KEYGUARD_START_DELAY, FADE_KEYGUARD_DURATION,
                    this::onLaunchTransitionFadingEnded);
            mCommandQueue.appTransitionStarting(mDisplayId, SystemClock.uptimeMillis(),
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        };
        if (mNotificationPanelViewController.isLaunchTransitionRunning()) {
            mNotificationPanelViewController.setLaunchTransitionEndRunnable(hideRunnable);
        } else {
            hideRunnable.run();
        }
    }

    private void cancelAfterLaunchTransitionRunnables() {
        if (mLaunchTransitionCancelRunnable != null) {
            mLaunchTransitionCancelRunnable.run();
        }
        mLaunchTransitionEndRunnable = null;
        mLaunchTransitionCancelRunnable = null;
        mNotificationPanelViewController.setLaunchTransitionEndRunnable(null);
    }

    /**
     * Fades the content of the Keyguard while we are dozing and makes it invisible when finished
     * fading.
     */
    public void fadeKeyguardWhilePulsing() {
        mNotificationPanelViewController.fadeOut(0, FADE_KEYGUARD_DURATION_PULSING,
                ()-> {
                hideKeyguard();
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }).start();
    }

    /**
     * Plays the animation when an activity that was occluding Keyguard goes away.
     */
    public void animateKeyguardUnoccluding() {
        mNotificationPanelViewController.setExpandedFraction(0f);
        mCommandQueueCallbacks.animateExpandNotificationsPanel();
        mScrimController.setUnocclusionAnimationRunning(true);
    }

    /**
     * Starts the timeout when we try to start the affordances on Keyguard. We usually rely that
     * Keyguard goes away via fadeKeyguardAfterLaunchTransition, however, that might not happen
     * because the launched app crashed or something else went wrong.
     */
    public void startLaunchTransitionTimeout() {
        mMessageRouter.sendMessageDelayed(
                MSG_LAUNCH_TRANSITION_TIMEOUT, LAUNCH_TRANSITION_TIMEOUT_MS);
    }

    private void onLaunchTransitionTimeout() {
        Log.w(TAG, "Launch transition: Timeout!");
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        mNotificationPanelViewController.resetViews(false /* animate */);
    }

    private void runLaunchTransitionEndRunnable() {
        mLaunchTransitionCancelRunnable = null;
        if (mLaunchTransitionEndRunnable != null) {
            Runnable r = mLaunchTransitionEndRunnable;

            // mLaunchTransitionEndRunnable might call showKeyguard, which would execute it again,
            // which would lead to infinite recursion. Protect against it.
            mLaunchTransitionEndRunnable = null;
            r.run();
        }
    }

    /**
     * @return true if we would like to stay in the shade, false if it should go away entirely
     */
    public boolean hideKeyguardImpl(boolean forceStateChange) {
        Trace.beginSection("CentralSurfaces#hideKeyguard");
        boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
        int previousState = mStatusBarStateController.getState();
        if (!(mStatusBarStateController.setState(StatusBarState.SHADE, forceStateChange))) {
            //TODO: StatusBarStateController should probably know about hiding the keyguard and
            // notify listeners.

            // If the state didn't change, we may still need to update public mode
            mLockscreenUserManager.updatePublicMode();
        }
        if (mStatusBarStateController.leaveOpenOnKeyguardHide()) {
            if (!mStatusBarStateController.isKeyguardRequested()) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            }
            long delay = mKeyguardStateController.calculateGoingToFullShadeDelay();
            mLockscreenShadeTransitionController.onHideKeyguard(delay, previousState);

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            mNavigationBarController.disableAnimationsDuringHide(mDisplayId, delay);
        } else if (!mNotificationPanelViewController.isCollapsing()) {
            instantCollapseNotificationPanel();
        }

        // Keyguard state has changed, but QS is not listening anymore. Make sure to update the tile
        // visibilities so next time we open the panel we know the correct height already.
        if (mQSPanelController != null) {
            mQSPanelController.refreshAllTiles();
        }
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        releaseGestureWakeLock();
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        mNotificationPanelViewController.resetAlpha();
        mNotificationPanelViewController.resetTranslation();
        mNotificationPanelViewController.resetViewGroupFade();
        updateDozingState();
        updateScrimController();
        Trace.endSection();
        return staying;
    }

    private void releaseGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
    }

    /**
     * Notifies the status bar that Keyguard is going away very soon.
     */
    public void keyguardGoingAway() {
        // Treat Keyguard exit animation as an app transition to achieve nice transition for status
        // bar.
        mKeyguardStateController.notifyKeyguardGoingAway(true);
        mCommandQueue.appTransitionPending(mDisplayId, true /* forced */);
        updateScrimController();
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     *  @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     * @param isBypassFading is this a fading away animation while bypassing
     */
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration,
            boolean isBypassFading) {
        mCommandQueue.appTransitionStarting(mDisplayId, startTime + fadeoutDuration
                        - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, fadeoutDuration > 0 /* animate */);
        mCommandQueue.appTransitionStarting(mDisplayId,
                    startTime - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mKeyguardStateController.notifyKeyguardFadingAway(delay, fadeoutDuration, isBypassFading);
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    public void finishKeyguardFadingAway() {
        mKeyguardStateController.notifyKeyguardDoneFading();
        mScrimController.setExpansionAffectsAlpha(true);

        // If the device was re-locked while unlocking, we might have a pending lock that was
        // delayed because the keyguard was in the middle of going away.
        mKeyguardViewMediator.maybeHandlePendingLock();
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {
        // Set additional scrim only if the lock and system wallpaper are different to prevent
        // applying the dimming effect twice.
        mUiBgExecutor.execute(() -> {
            float dimAmount = 0f;
            if (mWallpaperManager.lockScreenWallpaperExists()) {
                dimAmount = mWallpaperManager.getWallpaperDimAmount();
            }
            final float scrimDimAmount = dimAmount;
            mMainExecutor.execute(() -> {
                mScrimController.setAdditionalScrimBehindAlphaKeyguard(scrimDimAmount);
                mScrimController.applyCompositeAlphaOnScrimBehindKeyguard();
            });
        });

        // Lock wallpaper defines the color of the majority of the views, hence we'll use it
        // to set our default theme.
        final boolean lockDarkText = mColorExtractor.getNeutralColors().supportsDarkText();
        final int themeResId = lockDarkText ? R.style.Theme_SystemUI_LightWallpaper
                : R.style.Theme_SystemUI;
        if (mContext.getThemeResId() != themeResId) {
            mContext.setTheme(themeResId);
            mConfigurationController.notifyThemeChanged();
        }
    }

    private void updateDozingState() {
        Trace.traceCounter(Trace.TRACE_TAG_APP, "dozing", mDozing ? 1 : 0);
        Trace.beginSection("CentralSurfaces#updateDozingState");

        boolean visibleNotOccluded = mStatusBarKeyguardViewManager.isShowing()
                && !mStatusBarKeyguardViewManager.isOccluded();
        // If we're dozing and we'll be animating the screen off, the keyguard isn't currently
        // visible but will be shortly for the animation, so we should proceed as if it's visible.
        boolean visibleNotOccludedOrWillBe =
                visibleNotOccluded || (mDozing && mDozeParameters.shouldDelayKeyguardShow());

        boolean wakeAndUnlock = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
        boolean animate = (!mDozing && mDozeServiceHost.shouldAnimateWakeup() && !wakeAndUnlock)
                || (mDozing && mDozeParameters.shouldControlScreenOff()
                && visibleNotOccludedOrWillBe);

        mNotificationPanelViewController.setDozing(mDozing, animate, mWakeUpTouchLocation);
        updateQsExpansionEnabled();
        Trace.endSection();
    }

    public void userActivity() {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardViewMediatorCallback.userActivity();
        }
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mState == StatusBarState.KEYGUARD
                && mStatusBarKeyguardViewManager.interceptMediaKey(event);
    }

    /**
     * While IME is active and a BACK event is detected, check with
     * {@link StatusBarKeyguardViewManager#dispatchBackKeyEventPreIme()} to see if the event
     * should be handled before routing to IME, in order to prevent the user having to hit back
     * twice to exit bouncer.
     */
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (mState == StatusBarState.KEYGUARD
                        && mStatusBarKeyguardViewManager.dispatchBackKeyEventPreIme()) {
                    return onBackPressed();
                }
        }
        return false;
    }

    protected boolean shouldUnlockOnMenuPressed() {
        return mDeviceInteractive && mState != StatusBarState.SHADE
            && mStatusBarKeyguardViewManager.shouldDismissOnMenuPressed();
    }

    public boolean onMenuPressed() {
        if (shouldUnlockOnMenuPressed()) {
            mShadeController.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    public void endAffordanceLaunch() {
        releaseGestureWakeLock();
        mNotificationPanelViewController.onAffordanceLaunchEnded();
    }

    public boolean onBackPressed() {
        final boolean isScrimmedBouncer =
                mScrimController.getState() == ScrimState.BOUNCER_SCRIMMED;
        final boolean isBouncerOverDream = isBouncerShowingOverDream();

        if (mStatusBarKeyguardViewManager.onBackPressed(
                isScrimmedBouncer || isBouncerOverDream /* hideImmediately */)) {
            if (isScrimmedBouncer || isBouncerOverDream) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            } else {
                mNotificationPanelViewController.expandWithoutQs();
            }
            return true;
        }
        if (mNotificationPanelViewController.isQsCustomizing()) {
            mNotificationPanelViewController.closeQsCustomizer();
            return true;
        }
        if (mNotificationPanelViewController.isQsExpanded()) {
            if (mNotificationPanelViewController.isQsDetailShowing()) {
                mNotificationPanelViewController.closeQsDetail();
            } else {
                mNotificationPanelViewController.animateCloseQs(false /* animateAway */);
            }
            return true;
        }
        if (mNotificationPanelViewController.closeUserSwitcherIfOpen()) {
            return true;
        }
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED
                && !isBouncerOverDream) {
            if (mNotificationPanelViewController.canPanelBeCollapsed()) {
                mShadeController.animateCollapsePanels();
            }
            return true;
        }
        return false;
    }

    public boolean onSpacePressed() {
        if (mDeviceInteractive && mState != StatusBarState.SHADE) {
            mShadeController.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    private void showBouncerOrLockScreenIfKeyguard() {
        // If the keyguard is animating away, we aren't really the keyguard anymore and should not
        // show the bouncer/lockscreen.
        if (!mKeyguardViewMediator.isHiding()
                && !mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()) {
            if (mState == StatusBarState.SHADE_LOCKED
                    && mKeyguardUpdateMonitor.isUdfpsEnrolled()) {
                // shade is showing while locked on the keyguard, so go back to showing the
                // lock screen where users can use the UDFPS affordance to enter the device
                mStatusBarKeyguardViewManager.reset(true);
            } else if ((mState == StatusBarState.KEYGUARD
                    && !mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing())
                    || mState == StatusBarState.SHADE_LOCKED) {
                mStatusBarKeyguardViewManager.showGenericBouncer(true /* scrimmed */);
            }
        }
    }

    /**
     * Show the bouncer if we're currently on the keyguard or shade locked and aren't hiding.
     * @param performAction the action to perform when the bouncer is dismissed.
     * @param cancelAction the action to perform when unlock is aborted.
     */
    public void showBouncerWithDimissAndCancelIfKeyguard(OnDismissAction performAction,
            Runnable cancelAction) {
        if ((mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED)
                && !mKeyguardViewMediator.isHiding()) {
            mStatusBarKeyguardViewManager.dismissWithAction(performAction, cancelAction,
                    false /* afterKeyguardGone */);
        } else if (cancelAction != null) {
            cancelAction.run();
        }
    }

    void instantCollapseNotificationPanel() {
        mNotificationPanelViewController.instantCollapse();
        mShadeController.runPostCollapseRunnables();
    }

    /**
     * Collapse the panel directly if we are on the main thread, post the collapsing on the main
     * thread if we are not.
     */
    void collapsePanelOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            mShadeController.collapsePanel();
        } else {
            mContext.getMainExecutor().execute(mShadeController::collapsePanel);
        }
    }

    /** Collapse the panel. The collapsing will be animated for the given {@code duration}. */
    void collapsePanelWithDuration(int duration) {
        mNotificationPanelViewController.collapseWithDuration(duration);
    }

    /**
     * Updates the light reveal effect to reflect the reason we're waking or sleeping (for example,
     * from the power button).
     * @param wakingUp Whether we're updating because we're waking up (true) or going to sleep
     *                 (false).
     */
    private void updateRevealEffect(boolean wakingUp) {
        if (mLightRevealScrim == null) {
            return;
        }

        final boolean wakingUpFromPowerButton = wakingUp
                && !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)
                && mWakefulnessLifecycle.getLastWakeReason()
                == PowerManager.WAKE_REASON_POWER_BUTTON;
        final boolean sleepingFromPowerButton = !wakingUp
                && mWakefulnessLifecycle.getLastSleepReason()
                == PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON;

        if (wakingUpFromPowerButton || sleepingFromPowerButton) {
            mLightRevealScrim.setRevealEffect(mPowerButtonReveal);
            mLightRevealScrim.setRevealAmount(1f - mStatusBarStateController.getDozeAmount());
        } else if (!wakingUp || !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)) {
            // If we're going to sleep, but it's not from the power button, use the default reveal.
            // If we're waking up, only use the default reveal if the biometric controller didn't
            // already set it to the circular reveal because we're waking up from a fingerprint/face
            // auth.
            mLightRevealScrim.setRevealEffect(LiftReveal.INSTANCE);
            mLightRevealScrim.setRevealAmount(1f - mStatusBarStateController.getDozeAmount());
        }
    }

    public LightRevealScrim getLightRevealScrim() {
        return mLightRevealScrim;
    }

    public void onTrackingStarted() {
        mShadeController.runPostCollapseRunnables();
    }

    public void onClosingFinished() {
        mShadeController.runPostCollapseRunnables();
        if (!mPresenter.isPresenterFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            mNotificationShadeWindowController.setNotificationShadeFocusable(true);
        }
    }

    public void onUnlockHintStarted() {
        mFalsingCollector.onUnlockHintStarted();
        mKeyguardIndicationController.showActionToUnlock();
    }

    public void onHintFinished() {
        // Delay the reset a bit so the user can read the text.
        mKeyguardIndicationController.hideTransientIndicationDelayed(HINT_RESET_DELAY_MS);
    }

    public void onCameraHintStarted() {
        mFalsingCollector.onCameraHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.camera_hint);
    }

    public void onVoiceAssistHintStarted() {
        mFalsingCollector.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.voice_hint);
    }

    public void onPhoneHintStarted() {
        mFalsingCollector.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.phone_hint);
    }

    public void onTrackingStopped(boolean expand) {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            if (!expand && !mKeyguardStateController.canDismissLockScreen()) {
                mStatusBarKeyguardViewManager.showBouncer(false /* scrimmed */);
            }
        }
    }

    // TODO: Figure out way to remove these.
    public NavigationBarView getNavigationBarView() {
        return mNavigationBarController.getNavigationBarView(mDisplayId);
    }

    public boolean isOverviewEnabled() {
        return mNavigationBarController.isOverviewEnabled(mDisplayId);
    }

    public void showPinningEnterExitToast(boolean entering) {
        mNavigationBarController.showPinningEnterExitToast(mDisplayId, entering);
    }

    public void showPinningEscapeToast() {
        mNavigationBarController.showPinningEscapeToast(mDisplayId);
    }

    /**
     * TODO: Remove this method. Views should not be passed forward. Will cause theme issues.
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mNotificationPanelViewController.getKeyguardBottomAreaView();
    }

    /**
     * Propagation of the bouncer state, indicating that it's fully visible.
     */
    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        mKeyguardBypassController.setBouncerShowing(bouncerShowing);
        mPulseExpansionHandler.setBouncerShowing(bouncerShowing);
        setBouncerShowingForStatusBarComponents(bouncerShowing);
        mStatusBarHideIconsForBouncerManager.setBouncerShowingAndTriggerUpdate(bouncerShowing);
        mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
        updateScrimController();
        if (!mBouncerShowing) {
            updatePanelExpansionForKeyguard();
        }
    }

    /**
     * Sets whether the bouncer over dream is showing. Note that the bouncer over dream is handled
     * independently of the rest of the notification panel. As a result, setting this state via
     * {@link #setBouncerShowing(boolean)} leads to unintended side effects from states modified
     * behind the dream.
     */
    public void setBouncerShowingOverDream(boolean bouncerShowingOverDream) {
        mBouncerShowingOverDream = bouncerShowingOverDream;
    }

    /**
     * Propagate the bouncer state to status bar components.
     *
     * Separate from {@link #setBouncerShowing} because we sometimes re-create the status bar and
     * should update only the status bar components.
     */
    private void setBouncerShowingForStatusBarComponents(boolean bouncerShowing) {
        int importance = bouncerShowing
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        if (mPhoneStatusBarViewController != null) {
            mPhoneStatusBarViewController.setImportantForAccessibility(importance);
        }
        mNotificationPanelViewController.setImportantForAccessibility(importance);
        mNotificationPanelViewController.setBouncerShowing(bouncerShowing);
    }

    /**
     * Collapses the notification shade if it is tracking or expanded.
     */
    public void collapseShade() {
        if (mNotificationPanelViewController.isTracking()) {
            mNotificationShadeWindowViewController.cancelCurrentTouch();
        }
        if (mPanelExpanded && mState == StatusBarState.SHADE) {
            mShadeController.animateCollapsePanels();
        }
    }

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mNotificationPanelViewController.onAffordanceLaunchEnded();
            releaseGestureWakeLock();
            mLaunchCameraWhenFinishedWaking = false;
            mDeviceInteractive = false;
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
            updateVisibleToUser();

            updateNotificationPanelTouchState();
            mNotificationShadeWindowViewController.cancelCurrentTouch();
            if (mLaunchCameraOnFinishedGoingToSleep) {
                mLaunchCameraOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the state
                // is correct.
                mMainExecutor.execute(() -> mCommandQueueCallbacks.onCameraLaunchGestureDetected(
                        mLastCameraLaunchSource));
            }

            if (mLaunchEmergencyActionOnFinishedGoingToSleep) {
                mLaunchEmergencyActionOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the
                // state is correct.
                mMainExecutor.execute(
                        () -> mCommandQueueCallbacks.onEmergencyActionLaunchGestureDetected());
            }
            updateIsKeyguard();
        }

        @Override
        public void onStartedGoingToSleep() {
            String tag = "CentralSurfaces#onStartedGoingToSleep";
            DejankUtils.startDetectingBlockingIpcs(tag);

            //  cancel stale runnables that could put the device in the wrong state
            cancelAfterLaunchTransitionRunnables();

            updateRevealEffect(false /* wakingUp */);
            updateNotificationPanelTouchState();
            maybeEscalateHeadsUp();
            dismissVolumeDialog();
            mWakeUpCoordinator.setFullyAwake(false);
            mKeyguardBypassController.onStartedGoingToSleep();

            // The unlocked screen off and fold to aod animations might use our LightRevealScrim -
            // we need to be expanded for it to be visible.
            if (mDozeParameters.shouldShowLightRevealScrim()) {
                makeExpandedVisible(true);
            }

            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        @Override
        public void onStartedWakingUp() {
            String tag = "CentralSurfaces#onStartedWakingUp";
            DejankUtils.startDetectingBlockingIpcs(tag);
            mNotificationShadeWindowController.batchApplyWindowLayoutParams(()-> {
                mDeviceInteractive = true;
                mWakeUpCoordinator.setWakingUp(true);
                if (!mKeyguardBypassController.getBypassEnabled()) {
                    mHeadsUpManager.releaseAllImmediately();
                }
                updateVisibleToUser();
                updateIsKeyguard();
                mDozeServiceHost.stopDozing();
                // This is intentionally below the stopDozing call above, since it avoids that we're
                // unnecessarily animating the wakeUp transition. Animations should only be enabled
                // once we fully woke up.
                updateRevealEffect(true /* wakingUp */);
                updateNotificationPanelTouchState();

                // If we are waking up during the screen off animation, we should undo making the
                // expanded visible (we did that so the LightRevealScrim would be visible).
                if (mScreenOffAnimationController.shouldHideLightRevealScrimOnWakeUp()) {
                    makeExpandedInvisible();
                }

            });
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        @Override
        public void onFinishedWakingUp() {
            mWakeUpCoordinator.setFullyAwake(true);
            mWakeUpCoordinator.setWakingUp(false);
            if (mLaunchCameraWhenFinishedWaking) {
                mNotificationPanelViewController.launchCamera(
                        false /* animate */, mLastCameraLaunchSource);
                mLaunchCameraWhenFinishedWaking = false;
            }
            if (mLaunchEmergencyActionWhenFinishedWaking) {
                mLaunchEmergencyActionWhenFinishedWaking = false;
                Intent emergencyIntent = getEmergencyActionIntent();
                if (emergencyIntent != null) {
                    mContext.startActivityAsUser(emergencyIntent,
                            getActivityUserHandle(emergencyIntent));
                }
            }
            updateScrimController();
        }
    };

    /**
     * We need to disable touch events because these might
     * collapse the panel after we expanded it, and thus we would end up with a blank
     * Keyguard.
     */
    void updateNotificationPanelTouchState() {
        boolean goingToSleepWithoutAnimation = isGoingToSleep()
                && !mDozeParameters.shouldControlScreenOff();
        boolean disabled = (!mDeviceInteractive && !mDozeServiceHost.isPulsing())
                || goingToSleepWithoutAnimation;
        mNotificationPanelViewController.setTouchAndAnimationDisabled(disabled);
        mNotificationIconAreaController.setAnimationsEnabled(!disabled);
    }

    final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurningOn(Runnable onDrawn) {
            mFalsingCollector.onScreenTurningOn();
            mNotificationPanelViewController.onScreenTurningOn();
        }

        @Override
        public void onScreenTurnedOn() {
            mScrimController.onScreenTurnedOn();
        }

        @Override
        public void onScreenTurnedOff() {
            Trace.beginSection("CentralSurfaces#onScreenTurnedOff");
            mFalsingCollector.onScreenOff();
            mScrimController.onScreenTurnedOff();
            if (mCloseQsBeforeScreenOff) {
                mNotificationPanelViewController.closeQs();
                mCloseQsBeforeScreenOff = false;
            }
            updateIsKeyguard();
            Trace.endSection();
        }
    };

    public int getWakefulnessState() {
        return mWakefulnessLifecycle.getWakefulness();
    }

    /**
     * @return true if the screen is currently fully off, i.e. has finished turning off and has
     * since not started turning on.
     */
    public boolean isScreenFullyOff() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF;
    }

    public void showScreenPinningRequest(int taskId, boolean allowCancel) {
        mScreenPinningRequest.showPrompt(taskId, allowCancel);
    }

    @Nullable Intent getEmergencyActionIntent() {
        Intent emergencyIntent = new Intent(EmergencyGesture.ACTION_LAUNCH_EMERGENCY);
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> emergencyActivities = pm.queryIntentActivities(emergencyIntent,
                PackageManager.MATCH_SYSTEM_ONLY);
        ResolveInfo resolveInfo = getTopEmergencySosInfo(emergencyActivities);
        if (resolveInfo == null) {
            Log.wtf(TAG, "Couldn't find an app to process the emergency intent.");
            return null;
        }
        emergencyIntent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name));
        emergencyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return emergencyIntent;
    }

    /**
     * Select and return the "best" ResolveInfo for Emergency SOS Activity.
     */
    private @Nullable ResolveInfo getTopEmergencySosInfo(List<ResolveInfo> emergencyActivities) {
        // No matched activity.
        if (emergencyActivities == null || emergencyActivities.isEmpty()) {
            return null;
        }

        // Of multiple matched Activities, give preference to the pre-set package name.
        String preferredAppPackageName =
                mContext.getString(R.string.config_preferredEmergencySosPackage);

        // If there is no preferred app, then return first match.
        if (TextUtils.isEmpty(preferredAppPackageName)) {
            return emergencyActivities.get(0);
        }

        for (ResolveInfo emergencyInfo: emergencyActivities) {
            // If activity is from the preferred app, use it.
            if (TextUtils.equals(emergencyInfo.activityInfo.packageName, preferredAppPackageName)) {
                return emergencyInfo;
            }
        }
        // No matching activity: return first match
        return emergencyActivities.get(0);
    }

    boolean isCameraAllowedByAdmin() {
        if (mDevicePolicyManager.getCameraDisabled(null,
                mLockscreenUserManager.getCurrentUserId())) {
            return false;
        } else if (mStatusBarKeyguardViewManager == null
                || (isKeyguardShowing() && isKeyguardSecure())) {
            // Check if the admin has disabled the camera specifically for the keyguard
            return (mDevicePolicyManager.getKeyguardDisabledFeatures(null,
                    mLockscreenUserManager.getCurrentUserId())
                    & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) == 0;
        }
        return true;
    }

    boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    boolean isWakingOrAwake() {
        return mWakefulnessLifecycle.getWakefulness() == WakefulnessLifecycle.WAKEFULNESS_WAKING
                || mWakefulnessLifecycle.getWakefulness() == WakefulnessLifecycle.WAKEFULNESS_AWAKE;
    }

    public void notifyBiometricAuthModeChanged() {
        mDozeServiceHost.updateDozing();
        updateScrimController();
    }

    /**
     * Set the amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade.
     */
    public void setTransitionToFullShadeProgress(float transitionToFullShadeProgress) {
        mTransitionToFullShadeProgress = transitionToFullShadeProgress;
    }

    /**
     * Sets the amount of progress to the bouncer being fully hidden/visible. 1 means the bouncer
     * is fully hidden, while 0 means the bouncer is visible.
     */
    public void setBouncerHiddenFraction(float expansion) {
        mScrimController.setBouncerHiddenFraction(expansion);
    }

    @VisibleForTesting
    public void updateScrimController() {
        Trace.beginSection("CentralSurfaces#updateScrimController");

        boolean unlocking = mKeyguardStateController.isShowing() && (
                mBiometricUnlockController.isWakeAndUnlock()
                        || mKeyguardStateController.isKeyguardFadingAway()
                        || mKeyguardStateController.isKeyguardGoingAway()
                        || mKeyguardViewMediator.requestedShowSurfaceBehindKeyguard()
                        || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehind());

        mScrimController.setExpansionAffectsAlpha(!unlocking);

        boolean launchingAffordanceWithPreview =
                mNotificationPanelViewController.isLaunchingAffordanceWithPreview();
        mScrimController.setLaunchingAffordanceWithPreview(launchingAffordanceWithPreview);

        if (mStatusBarKeyguardViewManager.isShowingAlternateAuth()) {
            if (mState == StatusBarState.SHADE || mState == StatusBarState.SHADE_LOCKED
                    || mTransitionToFullShadeProgress > 0f) {
                mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED_SHADE);
            } else {
                mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED);
            }
        } else if (mBouncerShowing && !unlocking) {
            // Bouncer needs the front scrim when it's on top of an activity,
            // tapping on a notification, editing QS or being dismissed by
            // FLAG_DISMISS_KEYGUARD_ACTIVITY.
            ScrimState state = mStatusBarKeyguardViewManager.bouncerNeedsScrimming()
                    ? ScrimState.BOUNCER_SCRIMMED : ScrimState.BOUNCER;
            mScrimController.transitionTo(state);
        } else if (launchingAffordanceWithPreview) {
            // We want to avoid animating when launching with a preview.
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        } else if (mBrightnessMirrorVisible) {
            mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        } else if (mState == StatusBarState.SHADE_LOCKED) {
            mScrimController.transitionTo(ScrimState.SHADE_LOCKED);
        } else if (mDozeServiceHost.isPulsing()) {
            mScrimController.transitionTo(ScrimState.PULSING,
                    mDozeScrimController.getScrimCallback());
        } else if (mDozeServiceHost.hasPendingScreenOffCallback()) {
            mScrimController.transitionTo(ScrimState.OFF, new ScrimController.Callback() {
                @Override
                public void onFinished() {
                    mDozeServiceHost.executePendingScreenOffCallback();
                }
            });
        } else if (mDozing && !unlocking) {
            mScrimController.transitionTo(ScrimState.AOD);
        } else if (mKeyguardStateController.isShowing() && !isOccluded() && !unlocking) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
        } else if (mKeyguardStateController.isShowing() && mKeyguardUpdateMonitor.isDreaming()) {
            mScrimController.transitionTo(ScrimState.DREAMING);
        } else {
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        }
        updateLightRevealScrimVisibility();

        Trace.endSection();
    }

    public boolean isKeyguardShowing() {
        if (mStatusBarKeyguardViewManager == null) {
            Slog.i(TAG, "isKeyguardShowing() called before startKeyguard(), returning true");
            return true;
        }
        return mStatusBarKeyguardViewManager.isShowing();
    }

    public boolean shouldIgnoreTouch() {
        return (mStatusBarStateController.isDozing()
                && mDozeServiceHost.getIgnoreTouchWhilePulsing())
                || mScreenOffAnimationController.shouldIgnoreKeyguardTouches();
    }

    // Begin Extra BaseStatusBar methods.

    protected final CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;

    // all notifications
    protected NotificationStackScrollLayout mStackScroller;

    // handling reordering
    private final VisualStabilityManager mVisualStabilityManager;

    protected AccessibilityManager mAccessibilityManager;

    protected boolean mDeviceInteractive;

    protected boolean mVisible;

    // mScreenOnFromKeyguard && mVisible.
    private boolean mVisibleToUser;

    protected DevicePolicyManager mDevicePolicyManager;
    private final PowerManager mPowerManager;
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    protected KeyguardManager mKeyguardManager;
    private final DeviceProvisionedController mDeviceProvisionedController;

    private final NavigationBarController mNavigationBarController;
    private final AccessibilityFloatingMenuController mAccessibilityFloatingMenuController;

    // UI-specific methods

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;
    private IDreamManager mDreamManager;

    protected Display mDisplay;
    private int mDisplayId;

    protected NotificationShelfController mNotificationShelfController;

    private final Lazy<AssistManager> mAssistManagerLazy;

    public boolean isDeviceInteractive() {
        return mDeviceInteractive;
    }

    private final BroadcastReceiver mBannerActionBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BANNER_ACTION_CANCEL.equals(action) || BANNER_ACTION_SETUP.equals(action)) {
                NotificationManager noMan = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                noMan.cancel(com.android.internal.messages.nano.SystemMessageProto.SystemMessage.
                        NOTE_HIDDEN_NOTIFICATIONS);

                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING, 0);
                if (BANNER_ACTION_SETUP.equals(action)) {
                    mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                            true /* force */);
                    mContext.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_REDACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    );
                }
            }
        }
    };

    public void setNotificationSnoozed(StatusBarNotification sbn, SnoozeOption snoozeOption) {
        mNotificationsController.setNotificationSnoozed(sbn, snoozeOption);
    }


    public void awakenDreams() {
        mUiBgExecutor.execute(() -> {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    protected void toggleKeyboardShortcuts(int deviceId) {
        KeyboardShortcuts.toggle(mContext, deviceId);
    }

    protected void dismissKeyboardShortcuts() {
        KeyboardShortcuts.dismiss();
    }

    /**
     * Dismiss the keyguard then execute an action.
     *
     * @param action The action to execute after dismissing the keyguard.
     * @param collapsePanel Whether we should collapse the panel after dismissing the keyguard.
     * @param willAnimateOnKeyguard Whether {@param action} will run an animation on the keyguard if
     *                              we are locked.
     */
    private void executeActionDismissingKeyguard(Runnable action, boolean afterKeyguardGone,
            boolean collapsePanel, boolean willAnimateOnKeyguard) {
        if (!mDeviceProvisionedController.isDeviceProvisioned()) return;

        OnDismissAction onDismissAction = new OnDismissAction() {
            @Override
            public boolean onDismiss() {
                new Thread(() -> {
                    try {
                        // The intent we are sending is for the application, which
                        // won't have permission to immediately start an activity after
                        // the user switches to home.  We know it is safe to do at this
                        // point, so make sure new activity switches are now allowed.
                        ActivityManager.getService().resumeAppSwitches();
                    } catch (RemoteException e) {
                    }
                    action.run();
                }).start();

                return collapsePanel ? mShadeController.collapsePanel() : willAnimateOnKeyguard;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return willAnimateOnKeyguard;
            }
        };
        dismissKeyguardThenExecute(onDismissAction, afterKeyguardGone);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(final PendingIntent intent) {
        startPendingIntentDismissingKeyguard(intent, null);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(
            final PendingIntent intent, @Nullable final Runnable intentSentUiThreadCallback) {
        startPendingIntentDismissingKeyguard(intent, intentSentUiThreadCallback,
                (ActivityLaunchAnimator.Controller) null);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentUiThreadCallback, View associatedView) {
        ActivityLaunchAnimator.Controller animationController = null;
        if (associatedView instanceof ExpandableNotificationRow) {
            animationController = mNotificationAnimationProvider.getAnimatorController(
                    ((ExpandableNotificationRow) associatedView));
        }

        startPendingIntentDismissingKeyguard(intent, intentSentUiThreadCallback,
                animationController);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(
            final PendingIntent intent, @Nullable final Runnable intentSentUiThreadCallback,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        final boolean willLaunchResolverActivity = intent.isActivity()
                && mActivityIntentHelper.wouldLaunchResolverActivity(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());

        boolean animate = !willLaunchResolverActivity
                && animationController != null
                && shouldAnimateLaunch(intent.isActivity());

        // If we animate, don't collapse the shade and defer the keyguard dismiss (in case we run
        // the animation on the keyguard). The animation will take care of (instantly) collapsing
        // the shade and hiding the keyguard once it is done.
        boolean collapse = !animate;
        executeActionDismissingKeyguard(() -> {
            try {
                // We wrap animationCallback with a StatusBarLaunchAnimatorController so that the
                // shade is collapsed after the animation (or when it is cancelled, aborted, etc).
                ActivityLaunchAnimator.Controller controller =
                        animationController != null ? new StatusBarLaunchAnimatorController(
                                animationController, this, intent.isActivity()) : null;

                mActivityLaunchAnimator.startPendingIntentWithAnimation(
                        controller, animate, intent.getCreatorPackage(),
                        (animationAdapter) -> {
                            ActivityOptions options = new ActivityOptions(
                                    getActivityOptions(mDisplayId, animationAdapter));
                            // TODO b/221255671: restrict this to only be set for notifications
                            options.setEligibleForLegacyPermissionPrompt(true);
                            return intent.sendAndReturnResult(null, 0, null, null, null,
                                    null, options.toBundle());
                        });
            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.
                // Just log the exception message.
                Log.w(TAG, "Sending intent failed: " + e);
                if (!collapse) {
                    // executeActionDismissingKeyguard did not collapse for us already.
                    collapsePanelOnMainThread();
                }
                // TODO: Dismiss Keyguard.
            }
            if (intent.isActivity()) {
                mAssistManagerLazy.get().hideAssist();
            }
            if (intentSentUiThreadCallback != null) {
                postOnUiThread(intentSentUiThreadCallback);
            }
        }, willLaunchResolverActivity, collapse, animate);
    }

    private void postOnUiThread(Runnable runnable) {
        mMainExecutor.execute(runnable);
    }

    /**
     * Returns an ActivityOptions bundle created using the given parameters.
     *
     * @param displayId The ID of the display to launch the activity in. Typically this would be the
     *                  display the status bar is on.
     * @param animationAdapter The animation adapter used to start this activity, or {@code null}
     *                         for the default animation.
     */
    public static Bundle getActivityOptions(int displayId,
            @Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options = getDefaultActivityOptions(animationAdapter);
        options.setLaunchDisplayId(displayId);
        options.setCallerDisplayId(displayId);
        return options.toBundle();
    }

    /**
     * Returns an ActivityOptions bundle created using the given parameters.
     *
     * @param displayId The ID of the display to launch the activity in. Typically this would be the
     *                  display the status bar is on.
     * @param animationAdapter The animation adapter used to start this activity, or {@code null}
     *                         for the default animation.
     * @param isKeyguardShowing Whether keyguard is currently showing.
     * @param eventTime The event time in milliseconds since boot, not including sleep. See
     *                  {@link ActivityOptions#setSourceInfo}.
     */
    public static Bundle getActivityOptions(int displayId,
            @Nullable RemoteAnimationAdapter animationAdapter, boolean isKeyguardShowing,
            long eventTime) {
        ActivityOptions options = getDefaultActivityOptions(animationAdapter);
        options.setSourceInfo(isKeyguardShowing ? ActivityOptions.SourceInfo.TYPE_LOCKSCREEN
                : ActivityOptions.SourceInfo.TYPE_NOTIFICATION, eventTime);
        options.setLaunchDisplayId(displayId);
        options.setCallerDisplayId(displayId);
        return options.toBundle();
    }

    public static ActivityOptions getDefaultActivityOptions(
            @Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options;
        if (animationAdapter != null) {
            if (ENABLE_SHELL_TRANSITIONS) {
                options = ActivityOptions.makeRemoteTransition(
                        RemoteTransitionAdapter.adaptRemoteAnimation(animationAdapter));
            } else {
                options = ActivityOptions.makeRemoteAnimation(animationAdapter);
            }
        } else {
            options = ActivityOptions.makeBasic();
        }
        return options;
    }

    void visibilityChanged(boolean visible) {
        if (mVisible != visible) {
            mVisible = visible;
            if (!visible) {
                mGutsManager.closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                        true /* removeControls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
            }
        }
        updateVisibleToUser();
    }

    protected void updateVisibleToUser() {
        boolean oldVisibleToUser = mVisibleToUser;
        mVisibleToUser = mVisible && mDeviceInteractive;

        if (oldVisibleToUser != mVisibleToUser) {
            handleVisibleToUserChanged(mVisibleToUser);
        }
    }

    /**
     * Clear Buzz/Beep/Blink.
     */
    public void clearNotificationEffects() {
        try {
            mBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    public boolean isBouncerShowing() {
        return mBouncerShowing;
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    public boolean isBouncerShowingScrimmed() {
        return isBouncerShowing() && mStatusBarKeyguardViewManager.bouncerNeedsScrimming();
    }

    public boolean isBouncerShowingOverDream() {
        return mBouncerShowingOverDream;
    }

    /**
     * When {@link KeyguardBouncer} starts to be dismissed, playing its animation.
     */
    public void onBouncerPreHideAnimation() {
        mNotificationPanelViewController.onBouncerPreHideAnimation();

    }

    /**
     * @return a PackageManger for userId or if userId is < 0 (USER_ALL etc) then
     *         return PackageManager for mContext
     */
    public static PackageManager getPackageManagerForUser(Context context, int userId) {
        Context contextForUser = context;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        context.createPackageContextAsUser(context.getPackageName(),
                        Context.CONTEXT_RESTRICTED,
                        new UserHandle(userId));
            } catch (NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    public boolean isKeyguardSecure() {
        if (mStatusBarKeyguardViewManager == null) {
            // startKeyguard() hasn't been called yet, so we don't know.
            // Make sure anything that needs to know isKeyguardSecure() checks and re-checks this
            // value onVisibilityChanged().
            Slog.w(TAG, "isKeyguardSecure() called before startKeyguard(), returning false",
                    new Throwable());
            return false;
        }
        return mStatusBarKeyguardViewManager.isSecure();
    }
    public NotificationPanelViewController getPanelController() {
        return mNotificationPanelViewController;
    }
    // End Extra BaseStatusBarMethods.

    public NotificationGutsManager getGutsManager() {
        return mGutsManager;
    }

    boolean isTransientShown() {
        return mTransientShown;
    }

    private void updateLightRevealScrimVisibility() {
        if (mLightRevealScrim == null) {
            // status bar may not be inflated yet
            return;
        }

        mLightRevealScrim.setAlpha(mScrimController.getState().getMaxLightRevealScrimAlpha());
    }

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
                    updateScrimController();
                    if (dreaming) {
                        maybeEscalateHeadsUp();
                    }
                }

                // TODO: (b/145659174) remove when moving to NewNotifPipeline. Replaced by
                //  KeyguardCoordinator
                @Override
                public void onStrongAuthStateChanged(int userId) {
                    super.onStrongAuthStateChanged(userId);
                    mNotificationsController.requestNotificationUpdate("onStrongAuthStateChanged");
                }
            };


    private final FalsingManager.FalsingBeliefListener mFalsingBeliefListener =
            new FalsingManager.FalsingBeliefListener() {
                @Override
                public void onFalse() {
                    // Hides quick settings, bouncer, and quick-quick settings.
                    mStatusBarKeyguardViewManager.reset(true);
                }
            };

    // Notifies StatusBarKeyguardViewManager every time the keyguard transition is over,
    // this animation is tied to the scrim for historic reasons.
    // TODO: notify when keyguard has faded away instead of the scrim.
    private final ScrimController.Callback mUnlockScrimCallback = new ScrimController
            .Callback() {
        @Override
        public void onFinished() {
            if (mStatusBarKeyguardViewManager == null) {
                Log.w(TAG, "Tried to notify keyguard visibility when "
                        + "mStatusBarKeyguardViewManager was null");
                return;
            }
            if (mKeyguardStateController.isKeyguardFadingAway()) {
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }
        }

        @Override
        public void onCancelled() {
            onFinished();
        }
    };

    private final DeviceProvisionedListener mUserSetupObserver = new DeviceProvisionedListener() {
        @Override
        public void onUserSetupChanged() {
            final boolean userSetup = mDeviceProvisionedController.isCurrentUserSetup();
            Log.d(TAG, "mUserSetupObserver - DeviceProvisionedListener called for "
                    + "current user");
            if (MULTIUSER_DEBUG) {
                Log.d(TAG, String.format("User setup changed: userSetup=%s mUserSetup=%s",
                        userSetup, mUserSetup));
            }

            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup) {
                    animateCollapseQuickSettings();
                }
                if (mNotificationPanelViewController != null) {
                    mNotificationPanelViewController.setUserSetupComplete(mUserSetup);
                }
                updateQsExpansionEnabled();
            }
        }
    };

    private final BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mWallpaperSupported) {
                // Receiver should not have been registered at all...
                Log.wtf(TAG, "WallpaperManager not supported");
                return;
            }
            WallpaperInfo info = mWallpaperManager.getWallpaperInfo(UserHandle.USER_CURRENT);
            mWallpaperController.onWallpaperInfoUpdated(info);

            final boolean deviceSupportsAodWallpaper = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_dozeSupportsAodWallpaper);
            // If WallpaperInfo is null, it must be ImageWallpaper.
            final boolean supportsAmbientMode = deviceSupportsAodWallpaper
                    && (info != null && info.supportsAmbientMode());

            mNotificationShadeWindowController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mScrimController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mKeyguardViewMediator.setWallpaperSupportsAmbientMode(supportsAmbientMode);
        }
    };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateResources();
            updateDisplaySize(); // populates mDisplayMetrics

            if (DEBUG) {
                Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
            }

            if (!mNotifPipelineFlags.isNewPipelineEnabled()) {
                mViewHierarchyManager.updateRowStates();
            }
            mScreenPinningRequest.onConfigurationChanged();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            // TODO: Remove this.
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onDensityOrFontScaleChanged();
            }
            // TODO: Bring these out of CentralSurfaces.
            mUserInfoControllerImpl.onDensityOrFontScaleChanged();
            mUserSwitcherController.onDensityOrFontScaleChanged();
            mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
            mHeadsUpManager.onDensityOrFontScaleChanged();
        }

        @Override
        public void onThemeChanged() {
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onOverlayChanged();
            }
            // We need the new R.id.keyguard_indication_area before recreating
            // mKeyguardIndicationController
            mNotificationPanelViewController.onThemeChanged();

            if (mStatusBarKeyguardViewManager != null) {
                mStatusBarKeyguardViewManager.onThemeChanged();
            }
            if (mAmbientIndicationContainer instanceof AutoReinflateContainer) {
                ((AutoReinflateContainer) mAmbientIndicationContainer).inflateLayout();
            }
            mNotificationIconAreaController.onThemeChanged();
        }

        @Override
        public void onUiModeChanged() {
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onUiModeChanged();
            }
        }
    };

    private StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStatePreChange(int oldState, int newState) {
                    // If we're visible and switched to SHADE_LOCKED (the user dragged
                    // down on the lockscreen), clear notification LED, vibration,
                    // ringing.
                    // Other transitions are covered in handleVisibleToUserChanged().
                    if (mVisible && (newState == StatusBarState.SHADE_LOCKED
                            || mStatusBarStateController.goingToFullShade())) {
                        clearNotificationEffects();
                    }
                    if (newState == StatusBarState.KEYGUARD) {
                        mRemoteInputManager.onPanelCollapsed();
                        maybeEscalateHeadsUp();
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    mState = newState;
                    updateReportRejectedTouchVisibility();
                    mDozeServiceHost.updateDozing();
                    updateTheme();
                    mNavigationBarController.touchAutoDim(mDisplayId);
                    Trace.beginSection("CentralSurfaces#updateKeyguardState");
                    if (mState == StatusBarState.KEYGUARD) {
                        mNotificationPanelViewController.cancelPendingPanelCollapse();
                    }
                    updateDozingState();
                    checkBarModes();
                    updateScrimController();
                    mPresenter.updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
                    Trace.endSection();
                }

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    if (mFeatureFlags.isEnabled(Flags.LOCKSCREEN_ANIMATIONS)
                            && !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)
                            && !mBiometricUnlockController.isWakeAndUnlock()) {
                        mLightRevealScrim.setRevealAmount(1f - linear);
                    }
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    Trace.beginSection("CentralSurfaces#updateDozing");
                    mDozing = isDozing;

                    // Collapse the notification panel if open
                    boolean dozingAnimated = mDozeServiceHost.getDozingRequested()
                            && mDozeParameters.shouldControlScreenOff();
                    mNotificationPanelViewController.resetViews(dozingAnimated);

                    updateQsExpansionEnabled();
                    mKeyguardViewMediator.setDozing(mDozing);

                    mNotificationsController.requestNotificationUpdate("onDozingChanged");
                    updateDozingState();
                    mDozeServiceHost.updateDozing();
                    updateScrimController();
                    updateReportRejectedTouchVisibility();
                    Trace.endSection();
                }

                @Override
                public void onFullscreenStateChanged(boolean isFullscreen) {
                    mIsFullscreen = isFullscreen;
                    maybeUpdateBarMode();
                }
            };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onPowerSaveChanged(boolean isPowerSave) {
                    mMainExecutor.execute(mCheckBarModes);
                    if (mDozeServiceHost != null) {
                        mDozeServiceHost.firePowerSaveChanged(isPowerSave);
                    }
                }
            };

    private final ActivityLaunchAnimator.Callback mActivityLaunchAnimatorCallback =
            new ActivityLaunchAnimator.Callback() {
                @Override
                public boolean isOnKeyguard() {
                    return mKeyguardStateController.isShowing();
                }

                @Override
                public void hideKeyguardWithAnimation(IRemoteAnimationRunner runner) {
                    // We post to the main thread for 2 reasons:
                    //   1. KeyguardViewMediator is not thread-safe.
                    //   2. To ensure that ViewMediatorCallback#keyguardDonePending is called before
                    //      ViewMediatorCallback#readyForKeyguardDone. The wrong order could occur
                    //      when doing
                    //      dismissKeyguardThenExecute { hideKeyguardWithAnimation(runner) }.
                    mMainExecutor.execute(() -> mKeyguardViewMediator.hideWithAnimation(runner));
                }

                @Override
                public int getBackgroundColor(TaskInfo task) {
                    if (!mStartingSurfaceOptional.isPresent()) {
                        Log.w(TAG, "No starting surface, defaulting to SystemBGColor");
                        return SplashscreenContentDrawer.getSystemBGColor();
                    }

                    return mStartingSurfaceOptional.get().getBackgroundColor(task);
                }
            };

    private final ActivityLaunchAnimator.Listener mActivityLaunchAnimatorListener =
            new ActivityLaunchAnimator.Listener() {
                @Override
                public void onLaunchAnimationStart() {
                    mKeyguardViewMediator.setBlursDisabledForAppLaunch(true);
                }

                @Override
                public void onLaunchAnimationEnd() {
                    mKeyguardViewMediator.setBlursDisabledForAppLaunch(false);
                }
            };

    private final DemoMode mDemoModeCallback = new DemoMode() {
        @Override
        public void onDemoModeFinished() {
            checkBarModes();
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) { }
    };

    /**
     *  Determines what UserHandle to use when launching an activity.
     *
     *  We want to ensure that activities that are launched within the systemui process should be
     *  launched as user of the current process.
     * @param intent
     * @return UserHandle
     */
    private UserHandle getActivityUserHandle(Intent intent) {
        if (intent.getComponent() != null
                && mContext.getPackageName().equals(intent.getComponent().getPackageName())) {
            return new UserHandle(UserHandle.myUserId());
        }
        return UserHandle.CURRENT;
    }
}
