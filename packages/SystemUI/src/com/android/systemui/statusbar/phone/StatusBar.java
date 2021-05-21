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
import static android.app.StatusBarManager.WindowType;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS;

import static androidx.lifecycle.Lifecycle.State.RESUMED;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;
import static com.android.systemui.charging.WirelessChargingLayout.UNKNOWN_BATTERY_LEVEL;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.PERMISSION_SELF;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_WARNING;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.wm.shell.bubbles.BubbleController.TASKBAR_CHANGED_BROADCAST;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
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
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.IWindowManager;
import android.view.InsetsState.InternalInsetsType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.widget.DateTimeView;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.view.AppearanceRegion;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.EventLogTags;
import com.android.systemui.InitController;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DelegateLaunchAnimatorController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.charging.WirelessChargingAnimation;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.emergency.EmergencyGesture;
import com.android.systemui.fragments.ExtensionFragmentListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
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
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.CircleReveal;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FeatureFlags;
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
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.charging.WiredChargingRippleController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneModule;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;
import javax.inject.Provider;

import dagger.Lazy;

public class StatusBar extends SystemUI implements DemoMode,
        ActivityStarter, KeyguardStateController.Callback,
        OnHeadsUpChangedListener, CommandQueue.Callbacks,
        ColorExtractor.OnColorsChangedListener, ConfigurationListener,
        StatusBarStateController.StateListener,
        LifecycleOwner, BatteryController.BatteryStateChangeCallback {
    public static final boolean MULTIUSER_DEBUG = false;

    protected static final int MSG_HIDE_RECENT_APPS = 1020;
    protected static final int MSG_PRELOAD_RECENT_APPS = 1022;
    protected static final int MSG_CANCEL_PRELOAD_RECENT_APPS = 1023;
    protected static final int MSG_TOGGLE_KEYBOARD_SHORTCUTS_MENU = 1026;
    protected static final int MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU = 1027;

    // Should match the values in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    static public final String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";

    private static final String BANNER_ACTION_CANCEL =
            "com.android.systemui.statusbar.banner_action_cancel";
    private static final String BANNER_ACTION_SETUP =
            "com.android.systemui.statusbar.banner_action_setup";
    public static final String TAG = "StatusBar";
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

    private static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
    private static final int MSG_CLOSE_PANELS = 1001;
    private static final int MSG_OPEN_SETTINGS_PANEL = 1002;
    private static final int MSG_LAUNCH_TRANSITION_TIMEOUT = 1003;
    // 1020-1040 reserved for BaseStatusBar

    // Time after we abort the launch transition.
    private static final long LAUNCH_TRANSITION_TIMEOUT_MS = 5000;

    protected static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    /**
     * The delay to reset the hint text when the hint animation is finished running.
     */
    private static final int HINT_RESET_DELAY_MS = 1200;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    public static final int FADE_KEYGUARD_START_DELAY = 100;
    public static final int FADE_KEYGUARD_DURATION = 300;
    public static final int FADE_KEYGUARD_DURATION_PULSING = 96;


    /** If true, the system is in the half-boot-to-decryption-screen state.
     * Prudently disable QS and notifications.  */
    public static final boolean ONLY_CORE_APPS;

    /** If true, the lockscreen will show a distinct wallpaper */
    public static final boolean ENABLE_LOCKSCREEN_WALLPAPER = true;

    private static final UiEventLogger sUiEventLogger = new UiEventLoggerImpl();

    static {
        boolean onlyCoreApps;
        try {
            IPackageManager packageManager =
                    IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            onlyCoreApps = packageManager.isOnlyCoreApps();
        } catch (RemoteException e) {
            onlyCoreApps = false;
        }
        ONLY_CORE_APPS = onlyCoreApps;
    }

    private LockscreenShadeTransitionController mLockscreenShadeTransitionController;

    public interface ExpansionChangedListener {
        void onExpansionChanged(float expansion, boolean expanded);
    }

    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState; // TODO: remove this. Just use StatusBarStateController
    protected boolean mBouncerShowing;

    private PhoneStatusBarPolicy mIconPolicy;
    private StatusBarSignalPolicy mSignalPolicy;

    private final VolumeComponent mVolumeComponent;
    private BrightnessMirrorController mBrightnessMirrorController;
    private boolean mBrightnessMirrorVisible;
    private BiometricUnlockController mBiometricUnlockController;
    private final LightBarController mLightBarController;
    private final Lazy<LockscreenWallpaper> mLockscreenWallpaperLazy;
    @Nullable
    protected LockscreenWallpaper mLockscreenWallpaper;
    private final AutoHideController mAutoHideController;
    @Nullable
    private final KeyguardLiftController mKeyguardLiftController;

    private final Point mCurrentDisplaySize = new Point();

    protected NotificationShadeWindowView mNotificationShadeWindowView;
    protected StatusBarWindowView mPhoneStatusBarWindow;
    protected PhoneStatusBarView mStatusBarView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    protected NotificationShadeWindowController mNotificationShadeWindowController;
    protected StatusBarWindowController mStatusBarWindowController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @VisibleForTesting
    DozeServiceHost mDozeServiceHost;
    private boolean mWakeUpComingFromTouch;
    private PointF mWakeUpTouchLocation;
    private LightRevealScrim mLightRevealScrim;
    private WiredChargingRippleController mChargingRippleAnimationController;
    private PowerButtonReveal mPowerButtonReveal;
    private CircleReveal mCircleReveal;
    private ValueAnimator mCircleRevealAnimator = ValueAnimator.ofFloat(0f, 1f);

    private final Object mQueueLock = new Object();

    private final PulseExpansionHandler mPulseExpansionHandler;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardStateController mKeyguardStateController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final BypassHeadsUpNotifier mBypassHeadsUpNotifier;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ConfigurationController mConfigurationController;
    protected NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private final DozeParameters mDozeParameters;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final Provider<StatusBarComponent.Builder> mStatusBarComponentBuilder;
    private final PluginManager mPluginManager;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final StatusBarNotificationActivityStarter.Builder
            mStatusBarNotificationActivityStarterBuilder;
    private final ShadeController mShadeController;
    private final SuperStatusBarViewFactory mSuperStatusBarViewFactory;
    private final LightsOutNotifController mLightsOutNotifController;
    private final InitController mInitController;

    private final PluginDependencyProvider mPluginDependencyProvider;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final ExtensionController mExtensionController;
    private final UserInfoControllerImpl mUserInfoControllerImpl;
    private final DismissCallbackRegistry mDismissCallbackRegistry;
    private final DemoModeController mDemoModeController;
    private NotificationsController mNotificationsController;
    private final OngoingCallController mOngoingCallController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final StatusBarLocationPublisher mStatusBarLocationPublisher;

    // expanded notifications
    // the sliding/resizing panel within the notification window
    protected NotificationPanelViewController mNotificationPanelViewController;

    // settings
    private QSPanelController mQSPanelController;

    KeyguardIndicationController mKeyguardIndicationController;

    private final RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;

    private View mReportRejectedTouch;

    private boolean mExpandedVisible;

    private final int[] mAbsPos = new int[2];

    private final NotificationGutsManager mGutsManager;
    private final NotificationLogger mNotificationLogger;
    private final NotificationViewHierarchyManager mViewHierarchyManager;
    private final KeyguardViewMediator mKeyguardViewMediator;
    protected final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final BrightnessSlider.Factory mBrightnessSliderFactory;
    private final FeatureFlags mFeatureFlags;

    private final List<ExpansionChangedListener> mExpansionChangedListeners;

    // for disabling the status bar
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int) */
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
    private final DeviceProvisionedListener mUserSetupObserver = new DeviceProvisionedListener() {
        @Override
        public void onUserSetupChanged() {
            final boolean userSetup = mDeviceProvisionedController.isUserSetup(
                    mDeviceProvisionedController.getCurrentUser());
            Log.d(TAG, "mUserSetupObserver - DeviceProvisionedListener called for user "
                    + mDeviceProvisionedController.getCurrentUser());
            if (MULTIUSER_DEBUG) {
                Log.d(TAG, String.format("User setup changed: userSetup=%s mUserSetup=%s",
                        userSetup, mUserSetup));
            }

            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mStatusBarView != null)
                    animateCollapseQuickSettings();
                if (mNotificationPanelViewController != null) {
                    mNotificationPanelViewController.setUserSetupComplete(mUserSetup);
                }
                updateQsExpansionEnabled();
            }
        }
    };

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

    protected final H mHandler = createHandler();

    private int mInteractingWindows;
    private @TransitionMode int mStatusBarMode;

    private ViewMediatorCallback mKeyguardViewMediatorCallback;
    private final ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;
    private final Executor mUiBgExecutor;

    protected boolean mDozing;

    private final NotificationMediaManager mMediaManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private boolean mWallpaperSupported;

    private final BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mWallpaperSupported) {
                // Receiver should not have been registered at all...
                Log.wtf(TAG, "WallpaperManager not supported");
                return;
            }
            WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
            WallpaperInfo info = wallpaperManager.getWallpaperInfo(UserHandle.USER_CURRENT);
            final boolean deviceSupportsAodWallpaper = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_dozeSupportsAodWallpaper);
            // If WallpaperInfo is null, it must be ImageWallpaper.
            final boolean supportsAmbientMode = deviceSupportsAodWallpaper
                    && (info != null && info.supportsAmbientMode());

            mNotificationShadeWindowController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mScrimController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
        }
    };

    BroadcastReceiver mTaskbarChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mBubblesOptional.isPresent()) {
                mBubblesOptional.get().onTaskbarChanged(intent.getExtras());
            }
        }
    };

    private Runnable mLaunchTransitionEndRunnable;
    private NotificationEntry mDraggedDownEntry;
    private boolean mLaunchCameraWhenFinishedWaking;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
    private boolean mLaunchEmergencyActionWhenFinishedWaking;
    private boolean mLaunchEmergencyActionOnFinishedGoingToSleep;
    private int mLastCameraLaunchSource;
    protected PowerManager.WakeLock mGestureWakeLock;
    private Vibrator mVibrator;
    private long[] mCameraLaunchGestureVibePattern;

    private final int[] mTmpInt2 = new int[2];

    // Fingerprint (as computed by getLoggingFingerprint() of the last logged state.
    private int mLastLoggedStateFingerprint;
    private boolean mTopHidesStatusBar;
    private boolean mStatusBarWindowHidden;
    private boolean mHideIconsForBouncer;
    private boolean mIsOccluded;
    private boolean mWereIconsJustHidden;
    private boolean mBouncerWasShowingWhenHidden;

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

    private final UserSwitcherController mUserSwitcherController;
    private final NetworkController mNetworkController;
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
    protected final BatteryController mBatteryController;
    protected boolean mPanelExpanded;
    private UiModeManager mUiModeManager;
    protected boolean mIsKeyguard;
    private LogMaker mStatusBarStateLog;
    protected final NotificationIconAreaController mNotificationIconAreaController;
    @Nullable private View mAmbientIndicationContainer;
    private final SysuiColorExtractor mColorExtractor;
    private final ScreenLifecycle mScreenLifecycle;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    private boolean mNoAnimationOnNextBarModeChange;
    private final SysuiStatusBarStateController mStatusBarStateController;

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
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
                    // Hides quick settings.
                    mNotificationPanelViewController.resetViews(true);
                    // Hides bouncer and quick-quick settings.
                    mStatusBarKeyguardViewManager.reset(true);
                }
            };

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private boolean mVibrateOnOpening;
    private final VibratorHelper mVibratorHelper;
    private ActivityLaunchAnimator mActivityLaunchAnimator;
    private NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    protected StatusBarNotificationPresenter mPresenter;
    private NotificationActivityStarter mNotificationActivityStarter;
    private Lazy<NotificationShadeDepthController> mNotificationShadeDepthControllerLazy;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final Optional<Bubbles> mBubblesOptional;
    private final Bubbles.BubbleExpandListener mBubbleExpandListener;

    private ActivityIntentHelper mActivityIntentHelper;
    private NotificationStackScrollLayoutController mStackScrollerController;

    /**
     * Public constructor for StatusBar.
     *
     * StatusBar is considered optional, and therefore can not be marked as @Inject directly.
     * Instead, an @Provide method is included. See {@link StatusBarPhoneModule}.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public StatusBar(
            Context context,
            NotificationsController notificationsController,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            StatusBarSignalPolicy signalPolicy,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManagerPhone headsUpManagerPhone,
            DynamicPrivacyController dynamicPrivacyController,
            BypassHeadsUpNotifier bypassHeadsUpNotifier,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            BroadcastDispatcher broadcastDispatcher,
            RemoteInputQuickSettingsDisabler remoteInputQuickSettingsDisabler,
            NotificationGutsManager notificationGutsManager,
            NotificationLogger notificationLogger,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationViewHierarchyManager notificationViewHierarchyManager,
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
            VibratorHelper vibratorHelper,
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
            @Nullable KeyguardLiftController keyguardLiftController,
            Lazy<LockscreenWallpaper> lockscreenWallpaperLazy,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            ScreenPinningRequest screenPinningRequest,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            Provider<StatusBarComponent.Builder> statusBarComponentBuilder,
            PluginManager pluginManager,
            Optional<LegacySplitScreen> splitScreenOptional,
            LightsOutNotifController lightsOutNotifController,
            StatusBarNotificationActivityStarter.Builder
                    statusBarNotificationActivityStarterBuilder,
            ShadeController shadeController,
            SuperStatusBarViewFactory superStatusBarViewFactory,
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
            DismissCallbackRegistry dismissCallbackRegistry,
            DemoModeController demoModeController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthControllerLazy,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            NotificationIconAreaController notificationIconAreaController,
            BrightnessSlider.Factory brightnessSliderFactory,
            WiredChargingRippleController chargingRippleAnimationController,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            FeatureFlags featureFlags,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController) {
        super(context);
        mNotificationsController = notificationsController;
        mLightBarController = lightBarController;
        mAutoHideController = autoHideController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mSignalPolicy = signalPolicy;
        mPulseExpansionHandler = pulseExpansionHandler;
        mWakeUpCoordinator = notificationWakeUpCoordinator;
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManagerPhone;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mDynamicPrivacyController = dynamicPrivacyController;
        mBypassHeadsUpNotifier = bypassHeadsUpNotifier;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mRemoteInputQuickSettingsDisabler = remoteInputQuickSettingsDisabler;
        mGutsManager = notificationGutsManager;
        mNotificationLogger = notificationLogger;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mViewHierarchyManager = notificationViewHierarchyManager;
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
        mVibratorHelper = vibratorHelper;
        mBubblesManagerOptional = bubblesManagerOptional;
        mBubblesOptional = bubblesOptional;
        mVisualStabilityManager = visualStabilityManager;
        mDeviceProvisionedController = deviceProvisionedController;
        mNavigationBarController = navigationBarController;
        mAssistManagerLazy = assistManagerLazy;
        mConfigurationController = configurationController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mDozeServiceHost = dozeServiceHost;
        mPowerManager = powerManager;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mKeyguardLiftController = keyguardLiftController;
        mLockscreenWallpaperLazy = lockscreenWallpaperLazy;
        mScreenPinningRequest = screenPinningRequest;
        mDozeScrimController = dozeScrimController;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mNotificationShadeDepthControllerLazy = notificationShadeDepthControllerLazy;
        mVolumeComponent = volumeComponent;
        mCommandQueue = commandQueue;
        mStatusBarComponentBuilder = statusBarComponentBuilder;
        mPluginManager = pluginManager;
        mSplitScreenOptional = splitScreenOptional;
        mStatusBarNotificationActivityStarterBuilder = statusBarNotificationActivityStarterBuilder;
        mShadeController = shadeController;
        mSuperStatusBarViewFactory = superStatusBarViewFactory;
        mLightsOutNotifController =  lightsOutNotifController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardViewMediatorCallback = viewMediatorCallback;
        mInitController = initController;
        mPluginDependencyProvider = pluginDependencyProvider;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mExtensionController = extensionController;
        mUserInfoControllerImpl = userInfoControllerImpl;
        mIconPolicy = phoneStatusBarPolicy;
        mDismissCallbackRegistry = dismissCallbackRegistry;
        mDemoModeController = demoModeController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBrightnessSliderFactory = brightnessSliderFactory;
        mChargingRippleAnimationController = chargingRippleAnimationController;
        mOngoingCallController = ongoingCallController;
        mAnimationScheduler = animationScheduler;
        mStatusBarLocationPublisher = locationPublisher;
        mFeatureFlags = featureFlags;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        lockscreenShadeTransitionController.setStatusbar(this);

        mExpansionChangedListeners = new ArrayList<>();

        mBubbleExpandListener =
                (isExpanding, key) -> {
                    mContext.getMainExecutor().execute(() -> {
                        mNotificationsController.requestNotificationUpdate("onBubbleExpandChanged");
                        updateScrimController();
                    });
                };

        mActivityIntentHelper = new ActivityIntentHelper(mContext);
        DateTimeView.setReceiverHandler(timeTickHandler);
    }

    @Override
    public void start() {
        mScreenLifecycle.addObserver(mScreenObserver);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mBypassHeadsUpNotifier.setUp();
        if (mBubblesOptional.isPresent()) {
            mBubblesOptional.get().setExpandListener(mBubbleExpandListener);
            IntentFilter filter = new IntentFilter(TASKBAR_CHANGED_BROADCAST);
            mBroadcastDispatcher.registerReceiver(mTaskbarChangeReceiver, filter);
        }

        mKeyguardIndicationController.init();

        mColorExtractor.addOnColorsChangedListener(this);
        mStatusBarStateController.addCallback(this,
                SysuiStatusBarStateController.RANK_STATUS_BAR);

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayId = mDisplay.getDisplayId();
        updateDisplaySize();

        mVibrateOnOpening = mContext.getResources().getBoolean(
                R.bool.config_vibrateOnIconAnimation);

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
        mWallpaperSupported =
                mContext.getSystemService(WallpaperManager.class).isWallpaperSupported();

        // Connect in to the status bar manager service
        mCommandQueue.addCallback(this);

        // Listen for demo mode changes
        mDemoModeController.addCallback(this);

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
        onSystemBarAttributesChanged(mDisplayId, result.mAppearance, result.mAppearanceRegions,
                result.mNavbarColorManagedByIme, result.mBehavior, result.mAppFullscreen);

        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(mDisplayId, result.mImeToken, result.mImeWindowVis,
                result.mImeBackDisposition, result.mShowImeSwitcher);

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
                null);

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

        mKeyguardStateController.addCallback(this);
        startKeyguard();

        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
        mDozeServiceHost.initialize(
                this,
                mStatusBarKeyguardViewManager,
                mNotificationShadeWindowViewController,
                mNotificationPanelViewController,
                mAmbientIndicationContainer);
        mDozeParameters.addCallback(this::updateLightRevealScrimVisibility);

        mConfigurationController.addCallback(this);

        mBatteryController.observe(mLifecycle, this);
        mLifecycle.setCurrentState(RESUMED);

        // set the initial view visibility
        int disabledFlags1 = result.mDisabledFlags1;
        int disabledFlags2 = result.mDisabledFlags2;
        mInitController.addPostInitTask(
                () -> setUpDisableFlags(disabledFlags1, disabledFlags2));

        mFalsingManager.addFalsingBeliefListener(mFalsingBeliefListener);

        mPluginManager.addPluginListener(
                new PluginListener<OverlayPlugin>() {
                    private ArraySet<OverlayPlugin> mOverlays = new ArraySet<>();

                    @Override
                    public void onPluginConnected(OverlayPlugin plugin, Context pluginContext) {
                        mMainThreadHandler.post(
                                () -> plugin.setup(getNotificationShadeWindowView(),
                                        getNavigationBarView(),
                                        new Callback(plugin), mDozeParameters));
                    }

                    @Override
                    public void onPluginDisconnected(OverlayPlugin plugin) {
                        mMainThreadHandler.post(() -> {
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
                            mMainThreadHandler.post(() -> {
                                mNotificationShadeWindowController
                                        .setStateListener(b -> mOverlays.forEach(
                                                o -> o.setCollapseDesired(b)));
                                mNotificationShadeWindowController
                                        .setForcePluginOpen(mOverlays.size() != 0, this);
                            });
                        }
                    }
                }, OverlayPlugin.class, true /* Allow multiple plugins */);
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
        final Context context = mContext;
        updateDisplaySize(); // populates mDisplayMetrics
        updateResources();
        updateTheme();

        inflateStatusBarWindow();
        mNotificationShadeWindowViewController.setService(this, mNotificationShadeWindowController);
        mNotificationShadeWindowView.setOnTouchListener(getStatusBarWindowTouchListener());

        // TODO: Deal with the ugliness that comes from having some of the statusbar broken out
        // into fragments, but the rest here, it leaves some awkward lifecycle and whatnot.
        mStackScrollerController =
                mNotificationPanelViewController.getNotificationStackScrollLayoutController();
        mStackScroller = mStackScrollerController.getView();
        NotificationListContainer notifListContainer =
                mStackScrollerController.getNotificationListContainer();
        mNotificationLogger.setUpWithContainer(notifListContainer);

        inflateShelf();
        mNotificationIconAreaController.setupShelf(mNotificationShelfController);
        mNotificationPanelViewController.addExpansionListener(mWakeUpCoordinator);
        mNotificationPanelViewController.addExpansionListener(
                this::dispatchPanelExpansionForKeyguardDismiss);

        // Allow plugins to reference DarkIconDispatcher and StatusBarStateController
        mPluginDependencyProvider.allowPluginDependency(DarkIconDispatcher.class);
        mPluginDependencyProvider.allowPluginDependency(StatusBarStateController.class);
        FragmentHostManager.get(mPhoneStatusBarWindow)
                .addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
                    CollapsedStatusBarFragment statusBarFragment =
                            (CollapsedStatusBarFragment) fragment;

                    PhoneStatusBarView oldStatusBarView = mStatusBarView;
                    mStatusBarView = (PhoneStatusBarView) statusBarFragment.getView();
                    mStatusBarView.setBar(this);
                    mStatusBarView.setPanel(mNotificationPanelViewController);
                    mStatusBarView.setScrimController(mScrimController);
                    mStatusBarView.setExpansionChangedListeners(mExpansionChangedListeners);

                    // CollapsedStatusBarFragment re-inflated PhoneStatusBarView and both of
                    // mStatusBarView.mExpanded and mStatusBarView.mBouncerShowing are false.
                    // PhoneStatusBarView's new instance will set to be gone in
                    // PanelBar.updateVisibility after calling mStatusBarView.setBouncerShowing
                    // that will trigger PanelBar.updateVisibility. If there is a heads up showing,
                    // it needs to notify PhoneStatusBarView's new instance to update the correct
                    // status by calling mNotificationPanel.notifyBarPanelExpansionChanged().
                    if (mHeadsUpManager.hasPinnedHeadsUp()) {
                        mNotificationPanelViewController.notifyBarPanelExpansionChanged();
                    }
                    mStatusBarView.setBouncerShowing(mBouncerShowing);
                    if (oldStatusBarView != null) {
                        float fraction = oldStatusBarView.getExpansionFraction();
                        boolean expanded = oldStatusBarView.isExpanded();
                        mStatusBarView.panelExpansionChanged(fraction, expanded);
                    }

                    HeadsUpAppearanceController oldController = mHeadsUpAppearanceController;
                    if (mHeadsUpAppearanceController != null) {
                        // This view is being recreated, let's destroy the old one
                        mHeadsUpAppearanceController.destroy();
                    }
                    // TODO: this should probably be scoped to the StatusBarComponent
                    // TODO (b/136993073) Separate notification shade and status bar
                    mHeadsUpAppearanceController = new HeadsUpAppearanceController(
                            mNotificationIconAreaController, mHeadsUpManager,
                            mStackScroller.getController(),
                            mStatusBarStateController, mKeyguardBypassController,
                            mKeyguardStateController, mWakeUpCoordinator, mCommandQueue,
                            mNotificationPanelViewController, mStatusBarView);
                    mHeadsUpAppearanceController.readFrom(oldController);

                    mLightsOutNotifController.setLightsOutNotifView(
                            mStatusBarView.findViewById(R.id.notification_lights_out));
                    mNotificationShadeWindowViewController.setStatusBarView(mStatusBarView);
                    checkBarModes();
                }).getFragmentManager()
                .beginTransaction()
                .replace(R.id.status_bar_container,
                        new CollapsedStatusBarFragment(
                                mOngoingCallController,
                                mAnimationScheduler,
                                mStatusBarLocationPublisher,
                                mNotificationIconAreaController),
                        CollapsedStatusBarFragment.TAG)
                .commit();

        mHeadsUpManager.setup(mVisualStabilityManager);
        mStatusBarTouchableRegionManager.setup(this, mNotificationShadeWindowView);
        mHeadsUpManager.addListener(this);
        mHeadsUpManager.addListener(mNotificationPanelViewController.getOnHeadsUpChangedListener());
        mHeadsUpManager.addListener(mVisualStabilityManager);
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
                return !mRemoteInputManager.getController().isRemoteInputActive();
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
        ScrimView scrimForBubble = mBubblesManagerOptional.isPresent()
                ? mBubblesManagerOptional.get().getScrimForBubble() : null;

        mScrimController.setScrimVisibleListener(scrimsVisible -> {
            mNotificationShadeWindowController.setScrimsVisibility(scrimsVisible);
        });
        mScrimController.attachViews(scrimBehind, notificationsScrim, scrimInFront, scrimForBubble);

        mLightRevealScrim = mNotificationShadeWindowView.findViewById(R.id.light_reveal_scrim);
        updateLightRevealScrimVisibility();

        mNotificationPanelViewController.initDependencies(
                this,
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
                    mQSPanelController.setBrightnessMirror(mBrightnessMirrorController);
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

        if (!mPowerManager.isScreenOn()) {
            mBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        }
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "GestureWakeLock");
        mVibrator = mContext.getSystemService(Vibrator.class);
        int[] pattern = mContext.getResources().getIntArray(
                R.array.config_cameraLaunchGestureVibePattern);
        mCameraLaunchGestureVibePattern = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            mCameraLaunchGestureVibePattern[i] = pattern[i];
        }

        // receive broadcasts
        registerBroadcastReceiver();

        IntentFilter demoFilter = new IntentFilter();
        if (DEBUG_MEDIA_FAKE_ARTWORK) {
            demoFilter.addAction(ACTION_FAKE_ARTWORK);
        }
        context.registerReceiverAsUser(mDemoReceiver, UserHandle.ALL, demoFilter,
                android.Manifest.permission.DUMP, null);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        mDeviceProvisionedController.addCallback(mUserSetupObserver);
        mUserSetupObserver.onUserSetupChanged();

        // disable profiling bars, since they overlap and clutter the output on app windows
        ThreadedRenderer.overrideProperty("disableProfileBars", "true");

        // Private API call to make the shadows look better for Recents
        ThreadedRenderer.overrideProperty("ambientRatio", String.valueOf(1.5f));
    }


    /**
     * When swiping up to dismiss the lock screen, the panel expansion goes from 1f to 0f. This
     * results in the clock/notifications/other content disappearing off the top of the screen.
     *
     * We also use the expansion amount to animate in the app/launcher surface from the bottom of
     * the screen, 'pushing' off the notifications and other content. To do this, we dispatch the
     * expansion amount to the KeyguardViewMediator if we're in the process of dismissing the
     * keyguard.
     */
    private void dispatchPanelExpansionForKeyguardDismiss(float expansion, boolean trackingTouch) {
        // Things that mean we're not dismissing the keyguard, and should ignore this expansion:
        // - Keyguard isn't even visible.
        // - Keyguard is visible, but can't be dismissed (swiping up will show PIN/password prompt).
        // - QS is expanded and we're swiping - swiping up now will hide QS, not dismiss the
        //   keyguard.
        if (!isKeyguardShowing()
                || !mKeyguardStateController.canDismissLockScreen()
                || (mNotificationPanelViewController.isQsExpanded() && trackingTouch)) {
            return;
        }

        // Otherwise, we should let the keyguard know about this if we're tracking touch, or if we
        // are already animating the keyguard dismiss (since we will need to either finish or cancel
        // the animation).
        if (trackingTouch
                || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe()) {
            mKeyguardStateController.notifyKeyguardDismissAmountChanged(
                    1f - expansion, trackingTouch);
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mHandler.post(mCheckBarModes);
        if (mDozeServiceHost != null) {
            mDozeServiceHost.firePowerSaveChanged(isPowerSave);
        }
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        // noop
    }

    @VisibleForTesting
    protected void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, null, UserHandle.ALL);
    }

    protected QS createDefaultQSFragment() {
        return FragmentHostManager.get(mNotificationShadeWindowView).create(QSFragment.class);
    }

    private void setUpPresenter() {
        // Set up the initial notification state.
        mActivityLaunchAnimator = new ActivityLaunchAnimator(mContext);
        mNotificationAnimationProvider = new NotificationLaunchAnimatorControllerProvider(
                mNotificationShadeWindowViewController,
                mStackScrollerController.getNotificationListContainer(),
                mNotificationShadeDepthControllerLazy.get(),
                mHeadsUpManager
        );

        // TODO: inject this.
        mPresenter = new StatusBarNotificationPresenter(mContext, mNotificationPanelViewController,
                mHeadsUpManager, mNotificationShadeWindowView, mStackScrollerController,
                mDozeScrimController, mScrimController, mNotificationShadeWindowController,
                mDynamicPrivacyController, mKeyguardStateController,
                mKeyguardIndicationController,
                this /* statusBar */, mShadeController,
                mLockscreenShadeTransitionController, mCommandQueue, mInitController,
                mNotificationInterruptStateProvider);

        mNotificationShelfController.setOnActivatedListener(mPresenter);
        mRemoteInputManager.getController().addCallback(mNotificationShadeWindowController);

        mNotificationActivityStarter =
                mStatusBarNotificationActivityStarterBuilder
                        .setStatusBar(this)
                        .setActivityLaunchAnimator(mActivityLaunchAnimator)
                        .setNotificationAnimatorControllerProvider(mNotificationAnimationProvider)
                        .setNotificationPresenter(mPresenter)
                        .setNotificationPanelViewController(mNotificationPanelViewController)
                        .build();
        mStackScroller.setNotificationActivityStarter(mNotificationActivityStarter);
        mGutsManager.setNotificationActivityStarter(mNotificationActivityStarter);

        mNotificationsController.initialize(
                this,
                mBubblesOptional,
                mPresenter,
                mStackScrollerController.getNotificationListContainer(),
                mNotificationActivityStarter,
                mPresenter);
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
        if (mDozing && !mKeyguardViewMediator.isAnimatingScreenOff()) {
            mPowerManager.wakeUp(
                    time, PowerManager.WAKE_REASON_GESTURE, "com.android.systemui:" + why);
            mWakeUpComingFromTouch = true;
            where.getLocationInWindow(mTmpInt2);
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
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mExpandedVisible) {
                    mShadeController.animateCollapsePanels();
                }
            }
            return mNotificationShadeWindowView.onTouchEvent(event);
        };
    }

    private void inflateShelf() {
        mNotificationShelfController = mSuperStatusBarViewFactory
                .getNotificationShelfController(mStackScroller);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        // TODO: Remove this.
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onDensityOrFontScaleChanged();
        }
        // TODO: Bring these out of StatusBar.
        mUserInfoControllerImpl.onDensityOrFontScaleChanged();
        mUserSwitcherController.onDensityOrFontScaleChanged();
        mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
        mHeadsUpManager.onDensityOrFontScaleChanged();
    }

    @Override
    public void onThemeChanged() {
        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.onThemeChanged();
        }
        if (mAmbientIndicationContainer instanceof AutoReinflateContainer) {
            ((AutoReinflateContainer) mAmbientIndicationContainer).inflateLayout();
        }
        mNotificationIconAreaController.onThemeChanged();
    }

    @Override
    public void onOverlayChanged() {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onOverlayChanged();
        }
        // We need the new R.id.keyguard_indication_area before recreating
        // mKeyguardIndicationController
        mNotificationPanelViewController.onThemeChanged();
        onThemeChanged();
    }

    @Override
    public void onUiModeChanged() {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onUiModeChanged();
        }
    }

    private void inflateStatusBarWindow() {
        mNotificationShadeWindowView = mSuperStatusBarViewFactory.getNotificationShadeWindowView();
        StatusBarComponent statusBarComponent = mStatusBarComponentBuilder.get()
                .statusBarWindowView(mNotificationShadeWindowView).build();
        mNotificationShadeWindowViewController = statusBarComponent
                .getNotificationShadeWindowViewController();
        mNotificationShadeWindowController.setNotificationShadeView(mNotificationShadeWindowView);
        mNotificationShadeWindowViewController.setupExpandedStatusBar();
        mStatusBarWindowController = statusBarComponent.getStatusBarWindowController();
        mPhoneStatusBarWindow = mSuperStatusBarViewFactory.getStatusBarWindowView();
        mNotificationPanelViewController = statusBarComponent.getNotificationPanelViewController();
        statusBarComponent.getLockIconViewController().init();
        statusBarComponent.getAuthRippleController().init();
    }

    protected void startKeyguard() {
        Trace.beginSection("StatusBar#startKeyguard");
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
                        StatusBar.this.notifyBiometricAuthModeChanged();
                    }

                    private void setWakeAndUnlocking(boolean wakeAndUnlocking) {
                        if (getNavigationBarView() != null) {
                            getNavigationBarView().setWakeAndUnlocking(wakeAndUnlocking);
                        }
                    }
                });
        mStatusBarKeyguardViewManager.registerStatusBar(
                /* statusBar= */ this, getBouncerContainer(),
                mNotificationPanelViewController, mBiometricUnlockController,
                mStackScroller, mKeyguardBypassController);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        mRemoteInputManager.getController().addCallback(mStatusBarKeyguardViewManager);
        mDynamicPrivacyController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);

        mLightBarController.setBiometricUnlockController(mBiometricUnlockController);
        mMediaManager.setBiometricUnlockController(mBiometricUnlockController);
        mKeyguardDismissUtil.setDismissHandler(this::executeWhenUnlocked);
        Trace.endSection();
    }

    protected View getStatusBarView() {
        return mStatusBarView;
    }

    public NotificationShadeWindowView getNotificationShadeWindowView() {
        return mNotificationShadeWindowView;
    }

    public StatusBarWindowView getStatusBarWindow() {
        return mPhoneStatusBarWindow;
    }

    public NotificationShadeWindowViewController getNotificationShadeWindowViewController() {
        return mNotificationShadeWindowViewController;
    }

    public NotificationPanelViewController getNotificationPanelViewController() {
        return mNotificationPanelViewController;
    }

    protected ViewGroup getBouncerContainer() {
        return mNotificationShadeWindowView;
    }

    public int getStatusBarHeight() {
        return mStatusBarWindowController.getStatusBarHeight();
    }

    public boolean toggleSplitScreenMode(int metricsDockAction, int metricsUndockAction) {
        if (!mSplitScreenOptional.isPresent()) {
            return false;
        }

        final LegacySplitScreen legacySplitScreen = mSplitScreenOptional.get();
        if (legacySplitScreen.isDividerVisible()) {
            if (legacySplitScreen.isMinimized() && !legacySplitScreen.isHomeStackResizable()) {
                // Undocking from the minimized state is not supported
                return false;
            }

            legacySplitScreen.onUndockingTask();
            if (metricsUndockAction != -1) {
                mMetricsLogger.action(metricsUndockAction);
            }
            return true;
        }

        if (legacySplitScreen.splitPrimaryTask()) {
            if (metricsDockAction != -1) {
                mMetricsLogger.action(metricsDockAction);
            }
            return true;
        }

        return false;
    }

    /**
     * Disable QS if device not provisioned.
     * If the user switcher is simple then disable QS during setup because
     * the user intends to use the lock screen user switcher, QS in not needed.
     */
    private void updateQsExpansionEnabled() {
        final boolean expandEnabled = mDeviceProvisionedController.isDeviceProvisioned()
                && (mUserSetup || mUserSwitcherController == null
                        || !mUserSwitcherController.isSimpleUserSwitcher())
                && !isShadeDisabled()
                && ((mDisabled2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) == 0)
                && !mDozing
                && !ONLY_CORE_APPS;
        mNotificationPanelViewController.setQsExpansionEnabled(expandEnabled);
        Log.d(TAG, "updateQsExpansionEnabled - QS Expand enabled: " + expandEnabled);
    }

    public boolean isShadeDisabled() {
        return (mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0;
    }

    public void addQsTile(ComponentName tile) {
        if (mQSPanelController != null && mQSPanelController.getHost() != null) {
            mQSPanelController.getHost().addTile(tile);
        }
    }

    public void remQsTile(ComponentName tile) {
        if (mQSPanelController != null && mQSPanelController.getHost() != null) {
            mQSPanelController.getHost().removeTile(tile);
        }
    }

    public void clickTile(ComponentName tile) {
        mQSPanelController.clickTile(tile);
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
    public void requestFaceAuth() {
        if (!mKeyguardStateController.canDismissLockScreen()) {
            mKeyguardUpdateMonitor.requestFaceAuth();
        }
    }

    private void updateReportRejectedTouchVisibility() {
        if (mReportRejectedTouch == null) {
            return;
        }
        mReportRejectedTouch.setVisibility(mState == StatusBarState.KEYGUARD && !mDozing
                && mFalsingCollector.isReportingEnabled() ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);

        animate &= mStatusBarWindowState != WINDOW_STATE_HIDDEN;
        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        mDisabled1 = state1;

        final int old2 = mDisabled2;
        final int diff2 = state2 ^ old2;
        mDisabled2 = state2;

        if (DEBUG) {
            Log.d(TAG, String.format("disable1: 0x%08x -> 0x%08x (diff1: 0x%08x)",
                old1, state1, diff1));
            Log.d(TAG, String.format("disable2: 0x%08x -> 0x%08x (diff2: 0x%08x)",
                old2, state2, diff2));
        }

        StringBuilder flagdbg = new StringBuilder();
        flagdbg.append("disable<");
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_EXPAND))                ? 'E' : 'e');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_EXPAND))                ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_NOTIFICATION_ICONS))    ? 'I' : 'i');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_NOTIFICATION_ICONS))    ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS))   ? 'A' : 'a');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_NOTIFICATION_ALERTS))   ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_SYSTEM_INFO))           ? 'S' : 's');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_SYSTEM_INFO))           ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_BACK))                  ? 'B' : 'b');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_BACK))                  ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_HOME))                  ? 'H' : 'h');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_HOME))                  ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_RECENT))                ? 'R' : 'r');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_RECENT))                ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_CLOCK))                 ? 'C' : 'c');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_CLOCK))                 ? '!' : ' ');
        flagdbg.append(0 != ((state1 & StatusBarManager.DISABLE_SEARCH))                ? 'S' : 's');
        flagdbg.append(0 != ((diff1  & StatusBarManager.DISABLE_SEARCH))                ? '!' : ' ');
        flagdbg.append("> disable2<");
        flagdbg.append(0 != ((state2 & StatusBarManager.DISABLE2_QUICK_SETTINGS))       ? 'Q' : 'q');
        flagdbg.append(0 != ((diff2  & StatusBarManager.DISABLE2_QUICK_SETTINGS))       ? '!' : ' ');
        flagdbg.append(0 != ((state2 & StatusBarManager.DISABLE2_SYSTEM_ICONS))         ? 'I' : 'i');
        flagdbg.append(0 != ((diff2  & StatusBarManager.DISABLE2_SYSTEM_ICONS))         ? '!' : ' ');
        flagdbg.append(0 != ((state2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE))   ? 'N' : 'n');
        flagdbg.append(0 != ((diff2  & StatusBarManager.DISABLE2_NOTIFICATION_SHADE))   ? '!' : ' ');
        flagdbg.append('>');
        Log.d(TAG, flagdbg.toString());

        if ((diff1 & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state1 & StatusBarManager.DISABLE_EXPAND) != 0) {
                mShadeController.animateCollapsePanels();
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_RECENT) != 0) {
            if ((state1 & StatusBarManager.DISABLE_RECENT) != 0) {
                // close recents if it's visible
                mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
                mHandler.sendEmptyMessage(MSG_HIDE_RECENT_APPS);
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
            if (areNotificationAlertsDisabled()) {
                mHeadsUpManager.releaseAllImmediately();
            }
        }

        if ((diff2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) {
            updateQsExpansionEnabled();
        }

        if ((diff2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
            updateQsExpansionEnabled();
            if ((state2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
                mShadeController.animateCollapsePanels();
            }
        }
    }

    boolean areNotificationAlertsDisabled() {
        return (mDisabled1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
    }

    protected H createHandler() {
        return new StatusBar.H();
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
            ActivityLaunchAnimator.Controller animationController) {
        startActivityDismissingKeyguard(intent, false, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */,
                0 /* flags */, animationController);
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        startActivityDismissingKeyguard(intent, false, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, callback, 0,
                null /* animationController */);
    }

    public void setQsExpanded(boolean expanded) {
        mNotificationShadeWindowController.setQsExpanded(expanded);
        mNotificationPanelViewController.setStatusAccessibilityImportance(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
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

    @Override
    public void onUnlockedChanged() {
        updateKeyguardState();
        logStateToEventlog();
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        if (inPinnedMode) {
            mNotificationShadeWindowController.setHeadsUpShowing(true);
            mStatusBarWindowController.setForceStatusBarVisible(true);
            if (mNotificationPanelViewController.isFullyCollapsed()) {
                // We need to ensure that the touchable region is updated before the window will be
                // resized, in order to not catch any touches. A layout will ensure that
                // onComputeInternalInsets will be called and after that we can resize the layout. Let's
                // make sure that the window stays small for one frame until the touchableRegion is set.
                mNotificationPanelViewController.getView().requestLayout();
                mNotificationShadeWindowController.setForceWindowCollapsed(true);
                mNotificationPanelViewController.getView().post(() -> {
                    mNotificationShadeWindowController.setForceWindowCollapsed(false);
                });
            }
        } else {
            boolean bypassKeyguard = mKeyguardBypassController.getBypassEnabled()
                    && mState == StatusBarState.KEYGUARD;
            if (!mNotificationPanelViewController.isFullyCollapsed()
                    || mNotificationPanelViewController.isTracking() || bypassKeyguard) {
                // We are currently tracking or is open and the shade doesn't need to be kept
                // open artificially.
                mNotificationShadeWindowController.setHeadsUpShowing(false);
                if (bypassKeyguard) {
                    mStatusBarWindowController.setForceStatusBarVisible(false);
                }
            } else {
                // we need to keep the panel open artificially, let's wait until the animation
                // is finished.
                mHeadsUpManager.setHeadsUpGoingAway(true);
                mNotificationPanelViewController.runAfterAnimationFinished(() -> {
                    if (!mHeadsUpManager.hasPinnedHeadsUp()) {
                        mNotificationShadeWindowController.setHeadsUpShowing(false);
                        mHeadsUpManager.setHeadsUpGoingAway(false);
                    }
                    mRemoteInputManager.onPanelCollapsed();
                });
            }
        }
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        mNotificationsController.requestNotificationUpdate("onHeadsUpStateChanged");
        if (mStatusBarStateController.isDozing() && isHeadsUp) {
            entry.setPulseSuppressed(false);
            mDozeServiceHost.fireNotificationPulse(entry);
            if (mDozeServiceHost.isPulsing()) {
                mDozeScrimController.cancelPendingPulseTimeout();
            }
        }
        if (!isHeadsUp && !mHeadsUpManager.hasNotifications()) {
            // There are no longer any notifications to show.  We should end the pulse now.
            mDozeScrimController.pulseOutNow();
        }
    }

    public void setPanelExpanded(boolean isExpanded) {
        if (mPanelExpanded != isExpanded) {
            mNotificationLogger.onPanelExpandedChanged(isExpanded);
        }
        mPanelExpanded = isExpanded;
        updateHideIconsForBouncer(false /* animate */);
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

    public boolean hideStatusBarIconsWhenExpanded() {
        return mNotificationPanelViewController.hideStatusBarIconsWhenExpanded();
    }

    @Override
    public void onColorsChanged(ColorExtractor extractor, int which) {
        updateTheme();
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
        return mIsOccluded;
    }

    public void setOccluded(boolean occluded) {
        mIsOccluded = occluded;
        mScrimController.setKeyguardOccluded(occluded);
        updateHideIconsForBouncer(false /* animate */);
    }

    public boolean hideStatusBarIconsForBouncer() {
        return mHideIconsForBouncer || mWereIconsJustHidden;
    }

    /**
     * Decides if the status bar (clock + notifications + signal cluster) should be visible
     * or not when showing the bouncer.
     *
     * We want to hide it when:
     * • User swipes up on the keyguard
     * • Locked activity that doesn't show a status bar requests the bouncer
     *
     * @param animate should the change of the icons be animated.
     */
    private void updateHideIconsForBouncer(boolean animate) {
        boolean hideBecauseApp = mTopHidesStatusBar && mIsOccluded
                && (mStatusBarWindowHidden || mBouncerShowing);
        boolean hideBecauseKeyguard = !mPanelExpanded && !mIsOccluded && mBouncerShowing;
        boolean shouldHideIconsForBouncer = hideBecauseApp || hideBecauseKeyguard;
        if (mHideIconsForBouncer != shouldHideIconsForBouncer) {
            mHideIconsForBouncer = shouldHideIconsForBouncer;
            if (!shouldHideIconsForBouncer && mBouncerWasShowingWhenHidden) {
                // We're delaying the showing, since most of the time the fullscreen app will
                // hide the icons again and we don't want them to fade in and out immediately again.
                mWereIconsJustHidden = true;
                mHandler.postDelayed(() -> {
                    mWereIconsJustHidden = false;
                    mCommandQueue.recomputeDisableFlags(mDisplayId, true);
                }, 500);
            } else {
                mCommandQueue.recomputeDisableFlags(mDisplayId, animate);
            }
        }
        if (shouldHideIconsForBouncer) {
            mBouncerWasShowingWhenHidden = mBouncerShowing;
        }
    }

    public boolean headsUpShouldBeVisible() {
        return mHeadsUpAppearanceController.shouldBeVisible();
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

    /** Whether we should animate an activity launch. */
    public boolean areLaunchAnimationsEnabled() {
        // TODO(b/184121838): Support lock screen launch animations.
        return mState == StatusBarState.SHADE && !isOccluded();
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

    @VisibleForTesting
    void setUserSetupForTest(boolean userSetup) {
        mUserSetup = userSetup;
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    protected class H extends Handler {
        @Override
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_TOGGLE_KEYBOARD_SHORTCUTS_MENU:
                    toggleKeyboardShortcuts(m.arg1);
                    break;
                case MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU:
                    dismissKeyboardShortcuts();
                    break;
                // End old BaseStatusBar.H handling.
                case MSG_OPEN_NOTIFICATION_PANEL:
                    animateExpandNotificationsPanel();
                    break;
                case MSG_OPEN_SETTINGS_PANEL:
                    animateExpandSettingsPanel((String) m.obj);
                    break;
                case MSG_CLOSE_PANELS:
                    mShadeController.animateCollapsePanels();
                    break;
                case MSG_LAUNCH_TRANSITION_TIMEOUT:
                    onLaunchTransitionTimeout();
                    break;
            }
        }
    }

    public void maybeEscalateHeadsUp() {
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
                    notification.fullScreenIntent.send();
                    entry.notifyFullScreenIntentLaunched();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        });
        mHeadsUpManager.releaseAllImmediately();
    }

    /**
     * Called for system navigation gestures. First action opens the panel, second opens
     * settings. Down action closes the entire panel.
     */
    @Override
    public void handleSystemKey(int key) {
        if (SPEW) Log.d(TAG, "handleNavigationKey: " + key);
        if (!mCommandQueue.panelsEnabled() || !mKeyguardUpdateMonitor.isDeviceInteractive()
                || mKeyguardStateController.isShowing() && !mKeyguardStateController.isOccluded()) {
            return;
        }

        // Panels are not available in setup
        if (!mUserSetup) return;

        if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP == key) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_UP);
            mNotificationPanelViewController.collapse(
                    false /* delayed */, 1.0f /* speedUpFactor */);
        } else if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN == key) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_DOWN);
            if (mNotificationPanelViewController.isFullyCollapsed()) {
                if (mVibrateOnOpening) {
                    mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
                }
                mNotificationPanelViewController.expand(true /* animate */);
                mStackScroller.setWillExpand(true);
                mHeadsUpManager.unpinAll(true /* userUnpinned */);
                mMetricsLogger.count(NotificationPanelView.COUNTER_PANEL_OPEN, 1);
            } else if (!mNotificationPanelViewController.isInSettings()
                    && !mNotificationPanelViewController.isExpanding()) {
                mNotificationPanelViewController.flingSettings(0 /* velocity */,
                        NotificationPanelView.FLING_EXPAND);
                mMetricsLogger.count(NotificationPanelView.COUNTER_PANEL_OPEN_QS, 1);
            }
        }

    }

    @Override
    public void showPinningEnterExitToast(boolean entering) {
        if (getNavigationBarView() != null) {
            getNavigationBarView().showPinningEnterExitToast(entering);
        }
    }

    @Override
    public void showPinningEscapeToast() {
        if (getNavigationBarView() != null) {
            getNavigationBarView().showPinningEscapeToast();
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
        mHandler.post(mShadeController::animateCollapsePanels);
    }

    public void postAnimateForceCollapsePanels() {
        mHandler.post(() -> mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE,
                true /* force */));
    }

    public void postAnimateOpenPanels() {
        mHandler.sendEmptyMessage(MSG_OPEN_SETTINGS_PANEL);
    }

    @Override
    public void togglePanel() {
        if (mPanelExpanded) {
            mShadeController.animateCollapsePanels();
        } else {
            animateExpandNotificationsPanel();
        }
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        mShadeController.animateCollapsePanels(flags, force, false /* delayed */,
                1.0f /* speedUpFactor */);
    }

    /**
     * Called by {@link ShadeController} when it calls
     * {@link ShadeController#animateCollapsePanels(int, boolean, boolean, float)}.
     */
    void postHideRecentApps() {
        if (!mHandler.hasMessages(MSG_HIDE_RECENT_APPS)) {
            mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
            mHandler.sendEmptyMessage(MSG_HIDE_RECENT_APPS);
        }
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

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!mCommandQueue.panelsEnabled()) {
            return ;
        }

        mNotificationPanelViewController.expandWithoutQs();

        if (false) postStartTracing();
    }

    @Override
    public void animateExpandSettingsPanel(@Nullable String subPanel) {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        // Settings are not available in setup
        if (!mUserSetup) return;

        if (subPanel != null) {
            mQSPanelController.openDetails(subPanel);
        }
        mNotificationPanelViewController.expandWithQs();

        if (false) postStartTracing();
    }

    public void animateCollapseQuickSettings() {
        if (mState == StatusBarState.SHADE) {
            mStatusBarView.collapsePanel(true, false /* delayed */, 1.0f /* speedUpFactor */);
        }
    }

    void makeExpandedInvisible() {
        if (SPEW) Log.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible || mNotificationShadeWindowView == null) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mStatusBarView.collapsePanel(/*animate=*/ false, false /* delayed*/,
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
            showBouncerIfKeyguard();
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

    public boolean interceptTouchEvent(MotionEvent event) {
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
            if (upOrCancel && !mExpandedVisible) {
                setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
            } else {
                setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
            }
        }
        return false;
    }

    boolean isSameStatusBarState(int state) {
        return mStatusBarWindowState == state;
    }

    public GestureRecorder getGestureRecorder() {
        return mGestureRec;
    }

    public BiometricUnlockController getBiometricUnlockController() {
        return mBiometricUnlockController;
    }

    @Override // CommandQueue
    public void setWindowState(
            int displayId, @WindowType int window, @WindowVisibleState int state) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean showing = state == WINDOW_STATE_SHOWING;
        if (mNotificationShadeWindowView != null
                && window == StatusBarManager.WINDOW_STATUS_BAR
                && mStatusBarWindowState != state) {
            mStatusBarWindowState = state;
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Status bar " + windowStateToString(state));
            if (mStatusBarView != null) {
                if (!showing && mState == StatusBarState.SHADE) {
                    mStatusBarView.collapsePanel(false /* animate */, false /* delayed */,
                            1.0f /* speedUpFactor */);
                }
                mStatusBarWindowHidden = state == WINDOW_STATE_HIDDEN;
                updateHideIconsForBouncer(false /* animate */);
            }
        }

        updateBubblesVisibility();
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, boolean isFullscreen) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean barModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            barModeChanged = updateBarMode(barMode(mTransientShown, appearance));
        }
        mLightBarController.onStatusBarAppearanceChanged(appearanceRegions, barModeChanged,
                mStatusBarMode, navbarColorManagedByIme);

        updateBubblesVisibility();
        mStatusBarStateController.setFullscreenState(isFullscreen);
    }

    @Override
    public void showTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR)) {
            return;
        }
        showTransientUnchecked();
    }

    private void showTransientUnchecked() {
        if (!mTransientShown) {
            mTransientShown = true;
            mNoAnimationOnNextBarModeChange = true;
            handleTransientChanged();
        }
    }

    @Override
    public void abortTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR)) {
            return;
        }
        clearTransient();
    }

    private void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            handleTransientChanged();
        }
    }

    private void handleTransientChanged() {
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

    private static @TransitionMode int barMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_STATUS_BARS;
        if (isTransient) {
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

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
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
                }, false).show(animationDelay);
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        setInteracting(StatusBarManager.WINDOW_NAVIGATION_BAR, running);
    }

    protected BarTransitions getStatusBarTransitions() {
        return mNotificationShadeWindowViewController.getBarTransitions();
    }

    public void checkBarModes() {
        if (mDemoModeController.isInDemoMode()) return;
        if (mNotificationShadeWindowViewController != null && getStatusBarTransitions() != null) {
            checkBarMode(mStatusBarMode, mStatusBarWindowState, getStatusBarTransitions());
        }
        mNavigationBarController.checkNavBarModes(mDisplayId);
        mNoAnimationOnNextBarModeChange = false;
    }

    // Called by NavigationBarFragment
    public void setQsScrimEnabled(boolean scrimEnabled) {
        mNotificationPanelViewController.setQsScrimEnabled(scrimEnabled);
    }

    /** Temporarily hides Bubbles if the status bar is hidden. */
    private void updateBubblesVisibility() {
        if (mBubblesOptional.isPresent()) {
            mBubblesOptional.get().onStatusBarVisibilityChanged(
                    mStatusBarMode != MODE_LIGHTS_OUT
                            && mStatusBarMode != MODE_LIGHTS_OUT_TRANSPARENT
                            && !mStatusBarWindowHidden);
        }
    }

    void checkBarMode(@TransitionMode int mode, @WindowVisibleState int windowState,
            BarTransitions transitions) {
        final boolean anim = !mNoAnimationOnNextBarModeChange && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN;
        transitions.transitionTo(mode, anim);
    }

    private void finishBarAnimations() {
        if (mNotificationShadeWindowController != null
                && mNotificationShadeWindowViewController.getBarTransitions() != null) {
            mNotificationShadeWindowViewController.getBarTransitions().finishAnimations();
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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

        pw.println("  StatusBarWindowView: ");
        if (mNotificationShadeWindowViewController != null) {
            mNotificationShadeWindowViewController.dump(fd, pw, args);
            dumpBarTransitions(pw, "PhoneStatusBarTransitions",
                    mNotificationShadeWindowViewController.getBarTransitions());
        }

        pw.println("  mMediaManager: ");
        if (mMediaManager != null) {
            mMediaManager.dump(fd, pw, args);
        }

        pw.println("  Panels: ");
        if (mNotificationPanelViewController != null) {
            pw.println("    mNotificationPanel="
                    + mNotificationPanelViewController.getView() + " params="
                    + mNotificationPanelViewController.getView().getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanelViewController.dump(fd, pw, args);
        }
        pw.println("  mStackScroller: ");
        if (mStackScroller instanceof Dumpable) {
            pw.print  ("      ");
            ((Dumpable) mStackScroller).dump(fd, pw, args);
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
            mKeyguardIndicationController.dump(fd, pw, args);
        }

        if (mScrimController != null) {
            mScrimController.dump(fd, pw, args);
        }

        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.dump(pw);
        }

        mNotificationsController.dump(fd, pw, args, DUMPTRUCK);

        if (DUMPTRUCK) {
            if (false) {
                pw.println("see the logcat for a dump of the views we have created.");
                // must happen on ui thread
                mHandler.post(() -> {
                    mStatusBarView.getLocationOnScreen(mAbsPos);
                    Log.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1] +
                            ") " + mStatusBarView.getWidth() + "x" + getStatusBarHeight());
                    mStatusBarView.debug();
                });
            }
        }

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(fd, pw, args);
        }

        if (mHeadsUpManager != null) {
            mHeadsUpManager.dump(fd, pw, args);
        } else {
            pw.println("  mHeadsUpManager: null");
        }

        if (mStatusBarTouchableRegionManager != null) {
            mStatusBarTouchableRegionManager.dump(fd, pw, args);
        } else {
            pw.println("  mStatusBarTouchableRegionManager: null");
        }

        if (mLightBarController != null) {
            mLightBarController.dump(fd, pw, args);
        }

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }

        pw.println("Camera gesture intents:");
        pw.println("   Insecure camera: " + CameraIntents.getInsecureCameraIntent(mContext));
        pw.println("   Secure camera: " + CameraIntents.getSecureCameraIntent(mContext));
        pw.println("   Override package: "
                + String.valueOf(CameraIntents.getOverrideCameraPackage(mContext)));
    }

    public static void dumpBarTransitions(PrintWriter pw, String var, BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        pw.println(BarTransitions.modeToString(transitions.getMode()));
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

    float getDisplayWidth() {
        return mDisplayMetrics.widthPixels;
    }

    float getDisplayHeight() {
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
                flags, null /* animationController */);
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, 0);
    }

    private void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            final boolean dismissShade, final boolean disallowEnterPictureInPictureWhileLaunching,
            final Callback callback, int flags,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        if (onlyProvisioned && !mDeviceProvisionedController.isDeviceProvisioned()) return;

        final boolean afterKeyguardGone = mActivityIntentHelper.wouldLaunchResolverActivity(
                intent, mLockscreenUserManager.getCurrentUserId());

        ActivityLaunchAnimator.Controller animController = wrapAnimationController(
                animationController, dismissShade);

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
                    areLaunchAnimationsEnabled(), (adapter) -> {
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
                        if (intent.getAction() == Settings.Panel.ACTION_VOLUME) {
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
                                    options.toBundle(), UserHandle.CURRENT.getIdentifier());
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
                afterKeyguardGone, true /* deferred */);
    }

    @Nullable
    private ActivityLaunchAnimator.Controller wrapAnimationController(
            @Nullable ActivityLaunchAnimator.Controller animationController, boolean dismissShade) {
        if (animationController == null) {
            return null;
        }

        View rootView = animationController.getLaunchContainer().getRootView();
        if (rootView == mSuperStatusBarViewFactory.getStatusBarWindowView()) {
            // We are animating a view in the status bar. We have to make sure that the status bar
            // window matches the full screen during the animation and that we are expanding the
            // view below the other status bar text.
            animationController.setLaunchContainer(
                    mStatusBarWindowController.getLaunchAnimationContainer());

            return new DelegateLaunchAnimatorController(animationController) {
                @Override
                public void onLaunchAnimationStart(boolean isExpandingFullyAbove) {
                    getDelegate().onLaunchAnimationStart(isExpandingFullyAbove);
                    mStatusBarWindowController.setLaunchAnimationRunning(true);
                }

                @Override
                public void onLaunchAnimationEnd(boolean isExpandingFullyAbove) {
                    getDelegate().onLaunchAnimationEnd(isExpandingFullyAbove);
                    mStatusBarWindowController.setLaunchAnimationRunning(false);
                }
            };
        }

        if (dismissShade && rootView == mNotificationShadeWindowView) {
            // We are animating a view in the shade. We have to make sure that we collapse it when
            // the animation ends or is cancelled.
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
        dismissKeyguardThenExecute(() -> {
            if (runnable != null) {
                if (mStatusBarKeyguardViewManager.isShowing()
                        && mStatusBarKeyguardViewManager.isOccluded()) {
                    mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
                } else {
                    AsyncTask.execute(runnable);
                }
            }
            if (dismissShade) {
                if (mExpandedVisible && !mBouncerShowing) {
                    mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                            true /* force */, true /* delayed*/);
                } else {

                    // Do it after DismissAction has been processed to conserve the needed ordering.
                    mHandler.post(mShadeController::runPostCollapseRunnables);
                }
            } else if (isInLaunchTransition()
                    && mNotificationPanelViewController.isLaunchTransitionFinished()) {

                // We are not dismissing the shade, but the launch transition is already finished,
                // so nobody will call readyForKeyguardDone anymore. Post it such that
                // keyguardDonePending gets called first.
                mHandler.post(mStatusBarKeyguardViewManager::readyForKeyguardDone);
            }
            return deferred;
        }, cancelAction, afterKeyguardGone);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Trace.beginSection("StatusBar#onReceive");
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                KeyboardShortcuts.dismiss();
                if (mRemoteInputManager.getController() != null) {
                    mRemoteInputManager.getController().closeRemoteInputs();
                }
                if (mBubblesOptional.isPresent() && mBubblesOptional.get().isStackExpanded()) {
                    mBubblesOptional.get().collapseStack();
                }
                if (mLockscreenUserManager.isCurrentProfile(getSendingUserId())) {
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                    mShadeController.animateCollapsePanels(flags);
                }
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mNotificationShadeWindowController != null) {
                    mNotificationShadeWindowController.setNotTouchable(false);
                }
                if (mBubblesOptional.isPresent() && mBubblesOptional.get().isStackExpanded()) {
                    // Post to main thread handler, since updating the UI.
                    mMainThreadHandler.post(() -> mBubblesOptional.get().collapseStack());
                }
                finishBarAnimations();
                resetUserExpandedStates();
            }
            else if (DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG.equals(action)) {
                mQSPanelController.showDeviceMonitoringDialog();
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

    @Override
    public void onConfigChanged(Configuration newConfig) {
        updateResources();
        updateDisplaySize(); // populates mDisplayMetrics

        if (DEBUG) {
            Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
        }

        mViewHierarchyManager.updateRowStates();
        mScreenPinningRequest.onConfigurationChanged();
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

        if (mStatusBarView != null) {
            mStatusBarView.updateResources();
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
             * See also StatusBar.setPanelExpanded for another place where we attempt to do this. */
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

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                Context.VIBRATOR_SERVICE);
        vib.vibrate(250, VIBRATION_ATTRIBUTES);
    }

    final Runnable mStartTracing = new Runnable() {
        @Override
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Log.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    final Runnable mStopTracing = () -> {
        android.os.Debug.stopMethodTracing();
        Log.d(TAG, "stopTracing");
        vibrate();
    };

    @Override
    public void postQSRunnableDismissingKeyguard(final Runnable runnable) {
        mHandler.post(() -> {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
            executeRunnableDismissingKeyguard(() -> mHandler.post(runnable), null, false, false,
                    false);
        });
    }

    @Override
    public void postStartActivityDismissingKeyguard(PendingIntent intent) {
        postStartActivityDismissingKeyguard(intent, null /* animationController */);
    }

    @Override
    public void postStartActivityDismissingKeyguard(final PendingIntent intent,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mHandler.post(() -> startPendingIntentDismissingKeyguard(intent,
                null /* intentSentUiThreadCallback */, animationController));
    }

    @Override
    public void postStartActivityDismissingKeyguard(final Intent intent, int delay) {
        postStartActivityDismissingKeyguard(intent, delay, null /* animationController */);
    }

    @Override
    public void postStartActivityDismissingKeyguard(Intent intent, int delay,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mHandler.postDelayed(
                () ->
                        startActivityDismissingKeyguard(intent, true /* onlyProvisioned */,
                                true /* dismissShade */,
                                false /* disallowEnterPictureInPictureWhileLaunching */,
                                null /* callback */,
                                0 /* flags */,
                                animationController),
                delay);
    }

    @Override
    public List<String> demoCommands() {
        List<String> s = new ArrayList<>();
        s.add(DemoMode.COMMAND_BARS);
        s.add(DemoMode.COMMAND_CLOCK);
        s.add(DemoMode.COMMAND_OPERATOR);
        return s;
    }

    @Override
    public void onDemoModeStarted() {
        // Must send this message to any view that we delegate to via dispatchDemoCommandToView
        dispatchDemoModeStartedToView(R.id.clock);
        dispatchDemoModeStartedToView(R.id.operator_name);
    }

    @Override
    public void onDemoModeFinished() {
        dispatchDemoModeFinishedToView(R.id.clock);
        dispatchDemoModeFinishedToView(R.id.operator_name);
        checkBarModes();
    }

    @Override
    public void dispatchDemoCommand(String command, @NonNull Bundle args) {
        if (command.equals(COMMAND_CLOCK)) {
            dispatchDemoCommandToView(command, args, R.id.clock);
        }
        if (command.equals(COMMAND_BARS)) {
            String mode = args.getString("mode");
            int barMode = "opaque".equals(mode) ? MODE_OPAQUE :
                    "translucent".equals(mode) ? MODE_TRANSLUCENT :
                    "semi-transparent".equals(mode) ? MODE_SEMI_TRANSPARENT :
                    "transparent".equals(mode) ? MODE_TRANSPARENT :
                    "warning".equals(mode) ? MODE_WARNING :
                    -1;
            if (barMode != -1) {
                boolean animate = true;
                if (mNotificationShadeWindowController != null
                        && mNotificationShadeWindowViewController.getBarTransitions() != null) {
                    mNotificationShadeWindowViewController.getBarTransitions().transitionTo(
                            barMode, animate);
                }
                mNavigationBarController.transitionTo(mDisplayId, barMode, animate);
            }
        }
        if (command.equals(COMMAND_OPERATOR)) {
            dispatchDemoCommandToView(command, args, R.id.operator_name);
        }
    }

    //TODO: these should have controllers, and this method should be removed
    private void dispatchDemoCommandToView(String command, Bundle args, int id) {
        if (mStatusBarView == null) return;
        View v = mStatusBarView.findViewById(id);
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).dispatchDemoCommand(command, args);
        }
    }

    private void dispatchDemoModeStartedToView(int id) {
        if (mStatusBarView == null) return;
        View v = mStatusBarView.findViewById(id);
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).onDemoModeStarted();
        }
    }

    private void dispatchDemoModeFinishedToView(int id) {
        if (mStatusBarView == null) return;
        View v = mStatusBarView.findViewById(id);
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).onDemoModeFinished();
        }
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

    /**
     * stop(tag)
     * @return True if StatusBar state is FULLSCREEN_USER_SWITCHER.
     */
    public boolean isFullScreenUserSwitcherState() {
        return mState == StatusBarState.FULLSCREEN_USER_SWITCHER;
    }

    boolean updateIsKeyguard() {
        boolean wakeAndUnlocking = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardForDozing = mDozeServiceHost.getDozingRequested()
                && (!mDeviceInteractive || isGoingToSleep() && (isScreenFullyOff() || mIsKeyguard));
        boolean shouldBeKeyguard = (mStatusBarStateController.isKeyguardRequested()
                || keyguardForDozing) && !wakeAndUnlocking;
        if (keyguardForDozing) {
            updatePanelExpansionForKeyguard();
        }
        if (shouldBeKeyguard) {
            if (isGoingToSleep()
                    && mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_OFF) {
                // Delay showing the keyguard until screen turned off.
            } else {
                showKeyguardImpl();
            }
        } else {
            return hideKeyguardImpl();
        }
        return false;
    }

    public void showKeyguardImpl() {
        mIsKeyguard = true;
        if (mKeyguardStateController.isLaunchTransitionFadingAway()) {
            mNotificationPanelViewController.cancelAnimation();
            onLaunchTransitionFadingEnded();
        }
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (mUserSwitcherController != null && mUserSwitcherController.useFullscreenUserSwitcher()) {
            mStatusBarStateController.setState(StatusBarState.FULLSCREEN_USER_SWITCHER);
        } else if (!mPulseExpansionHandler.isWakingToShadeLocked()) {
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
        updatePanelExpansionForKeyguard();
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mBiometricUnlockController.getMode()
                != BiometricUnlockController.MODE_WAKE_AND_UNLOCK && !mBouncerShowing) {
            mShadeController.instantExpandNotificationsPanel();
        } else if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            instantCollapseNotificationPanel();
        }
    }

    private void onLaunchTransitionFadingEnded() {
        mNotificationPanelViewController.setAlpha(1.0f);
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
     * @param endRunnable the runnable to be run when the transition is done
     */
    public void fadeKeyguardAfterLaunchTransition(final Runnable beforeFading,
            Runnable endRunnable) {
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        mLaunchTransitionEndRunnable = endRunnable;
        Runnable hideRunnable = () -> {
            mKeyguardStateController.setLaunchTransitionFadingAway(true);
            if (beforeFading != null) {
                beforeFading.run();
            }
            updateScrimController();
            mPresenter.updateMediaMetaData(false, true);
            mNotificationPanelViewController.setAlpha(1);
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

    /**
     * Fades the content of the Keyguard while we are dozing and makes it invisible when finished
     * fading.
     */
    public void fadeKeyguardWhilePulsing() {
        mNotificationPanelViewController.fadeOut(0, FADE_KEYGUARD_DURATION_PULSING,
                ()-> {
                hideKeyguard();
                if (shouldShowCircleReveal()) {
                    startCircleReveal();
                }
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }).start();
    }

    /**
     * Plays the animation when an activity that was occluding Keyguard goes away.
     */
    public void animateKeyguardUnoccluding() {
        mNotificationPanelViewController.setExpandedFraction(0f);
        animateExpandNotificationsPanel();
    }

    /**
     * Starts the timeout when we try to start the affordances on Keyguard. We usually rely that
     * Keyguard goes away via fadeKeyguardAfterLaunchTransition, however, that might not happen
     * because the launched app crashed or something else went wrong.
     */
    public void startLaunchTransitionTimeout() {
        mHandler.sendEmptyMessageDelayed(MSG_LAUNCH_TRANSITION_TIMEOUT,
                LAUNCH_TRANSITION_TIMEOUT_MS);
    }

    private void onLaunchTransitionTimeout() {
        Log.w(TAG, "Launch transition: Timeout!");
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        mNotificationPanelViewController.resetViews(false /* animate */);
    }

    private void runLaunchTransitionEndRunnable() {
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
    public boolean hideKeyguardImpl() {
        mIsKeyguard = false;
        Trace.beginSection("StatusBar#hideKeyguard");
        boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
        if (!(mStatusBarStateController.setState(StatusBarState.SHADE))) {
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
            mLockscreenShadeTransitionController.onHideKeyguard(delay);

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
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        releaseGestureWakeLock();
        mNotificationPanelViewController.onAffordanceLaunchEnded();
        mNotificationPanelViewController.cancelAnimation();
        mNotificationPanelViewController.setAlpha(1f);
        mNotificationPanelViewController.resetViewGroupFade();
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
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {

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
        Trace.beginSection("StatusBar#updateDozingState");

        boolean visibleNotOccluded = mStatusBarKeyguardViewManager.isShowing()
                && !mStatusBarKeyguardViewManager.isOccluded();
        boolean wakeAndUnlock = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
        boolean animate = (!mDozing && mDozeServiceHost.shouldAnimateWakeup() && !wakeAndUnlock)
                || (mDozing && mDozeServiceHost.shouldAnimateScreenOff() && visibleNotOccluded);

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
     * {@link StatusBarKeyguardViewManager#dispatchBackKeyEventPreIme(KeyEvent)} to see if the event
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
        boolean isScrimmedBouncer = mScrimController.getState() == ScrimState.BOUNCER_SCRIMMED;
        if (mStatusBarKeyguardViewManager.onBackPressed(isScrimmedBouncer /* hideImmediately */)) {
            if (isScrimmedBouncer) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            } else {
                mNotificationPanelViewController.expandWithoutQs();
            }
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
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
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

    private void showBouncerIfKeyguard() {
        if (!mKeyguardViewMediator.isHiding()) {
            if (mState == StatusBarState.KEYGUARD
                    && !mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing()) {
                mStatusBarKeyguardViewManager.showGenericBouncer(true /* scrimmed */);
            } else if (mState == StatusBarState.SHADE_LOCKED) {
                mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
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
        Trace.beginSection("StatusBar#updateKeyguardState");
        if (mState == StatusBarState.KEYGUARD && mStatusBarView != null) {
            mStatusBarView.removePendingHideExpandedRunnables();
        }
        updateDozingState();
        checkBarModes();
        updateScrimController();
        mPresenter.updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
        updateKeyguardState();
        Trace.endSection();
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        if (mFeatureFlags.useNewLockscreenAnimations()) {
            mLightRevealScrim.setRevealAmount(1f - linear);
        }
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        Trace.beginSection("StatusBar#updateDozing");
        mDozing = isDozing;

        // Collapse the notification panel if open
        boolean dozingAnimated = mDozeServiceHost.getDozingRequested()
                && mDozeParameters.shouldControlScreenOff();
        mNotificationPanelViewController.resetViews(dozingAnimated);

        updateQsExpansionEnabled();
        mKeyguardViewMediator.setDozing(mDozing);

        if (!isDozing && shouldShowCircleReveal()) {
            startCircleReveal();
        } else if ((isDozing && mWakefulnessLifecycle.getLastSleepReason()
                == PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON)
                || (!isDozing && mWakefulnessLifecycle.getLastWakeReason()
                == PowerManager.WAKE_REASON_POWER_BUTTON)) {
            mLightRevealScrim.setRevealEffect(mPowerButtonReveal);
        } else if (!mCircleRevealAnimator.isRunning()) {
            mLightRevealScrim.setRevealEffect(LiftReveal.INSTANCE);
        }

        mNotificationsController.requestNotificationUpdate("onDozingChanged");
        updateDozingState();
        mDozeServiceHost.updateDozing();
        updateScrimController();
        updateReportRejectedTouchVisibility();
        Trace.endSection();
    }

    private void startCircleReveal() {
        mLightRevealScrim.setRevealEffect(mCircleReveal);
        mCircleRevealAnimator.cancel();
        mCircleRevealAnimator.addUpdateListener(animation ->
                mLightRevealScrim.setRevealAmount(
                        (float) mCircleRevealAnimator.getAnimatedValue()));
        mCircleRevealAnimator.setDuration(900);
        mCircleRevealAnimator.start();
    }

    private boolean shouldShowCircleReveal() {
        return mCircleReveal != null && !mCircleRevealAnimator.isRunning()
                && mKeyguardUpdateMonitor.isUdfpsEnrolled()
                && mBiometricUnlockController.getBiometricType() == FINGERPRINT;
    }

    private void updateKeyguardState() {
        mKeyguardStateController.notifyKeyguardState(mStatusBarKeyguardViewManager.isShowing(),
                mStatusBarKeyguardViewManager.isOccluded());
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
        mKeyguardIndicationController.showTransientIndication(R.string.keyguard_unlock);
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
        if (mStatusBarView != null) mStatusBarView.setBouncerShowing(bouncerShowing);
        updateHideIconsForBouncer(true /* animate */);
        mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
        updateScrimController();
        if (!mBouncerShowing) {
            updatePanelExpansionForKeyguard();
        }
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
                mHandler.post(() -> onCameraLaunchGestureDetected(mLastCameraLaunchSource));
            }

            if (mLaunchEmergencyActionOnFinishedGoingToSleep) {
                mLaunchEmergencyActionOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the
                // state is correct.
                mHandler.post(() -> onEmergencyActionLaunchGestureDetected());
            }
            updateIsKeyguard();
        }

        @Override
        public void onStartedGoingToSleep() {
            String tag = "StatusBar#onStartedGoingToSleep";
            DejankUtils.startDetectingBlockingIpcs(tag);
            updateNotificationPanelTouchState();
            notifyHeadsUpGoingToSleep();
            dismissVolumeDialog();
            mWakeUpCoordinator.setFullyAwake(false);
            mBypassHeadsUpNotifier.setFullyAwake(false);
            mKeyguardBypassController.onStartedGoingToSleep();
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        @Override
        public void onStartedWakingUp() {
            String tag = "StatusBar#onStartedWakingUp";
            DejankUtils.startDetectingBlockingIpcs(tag);
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
            updateNotificationPanelTouchState();
            mPulseExpansionHandler.onStartedWakingUp();
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        @Override
        public void onFinishedWakingUp() {
            mWakeUpCoordinator.setFullyAwake(true);
            mBypassHeadsUpNotifier.setFullyAwake(true);
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
                    mContext.startActivityAsUser(emergencyIntent, UserHandle.CURRENT);
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
        public void onScreenTurningOn() {
            mFalsingCollector.onScreenTurningOn();
            mNotificationPanelViewController.onScreenTurningOn();
        }

        @Override
        public void onScreenTurnedOn() {
            mScrimController.onScreenTurnedOn();
        }

        @Override
        public void onScreenTurnedOff() {
            mFalsingCollector.onScreenOff();
            mScrimController.onScreenTurnedOff();
            updateIsKeyguard();
        }
    };

    public int getWakefulnessState() {
        return mWakefulnessLifecycle.getWakefulness();
    }

    private void vibrateForCameraGesture() {
        // Make sure to pass -1 for repeat so VibratorService doesn't stop us when going to sleep.
        mVibrator.vibrate(mCameraLaunchGestureVibePattern, -1 /* repeat */);
    }

    /**
     * @return true if the screen is currently fully off, i.e. has finished turning off and has
     *         since not started turning on.
     */
    public boolean isScreenFullyOff() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF;
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
        if (mKeyguardStateController.isShowing()) {
            // Don't allow apps to trigger this from keyguard.
            return;
        }
        // Show screen pinning request, since this comes from an app, show 'no thanks', button.
        showScreenPinningRequest(taskId, true);
    }

    public void showScreenPinningRequest(int taskId, boolean allowCancel) {
        mScreenPinningRequest.showPrompt(taskId, allowCancel);
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        if (displayId == mDisplayId) {
            mSplitScreenOptional.ifPresent(splitScreen -> splitScreen.onAppTransitionFinished());
        }
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (displayId == mDisplayId) {
            mSplitScreenOptional.ifPresent(splitScreen -> splitScreen.onAppTransitionFinished());
        }
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mLastCameraLaunchSource = source;
        if (isGoingToSleep()) {
            if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Finish going to sleep before launching camera");
            mLaunchCameraOnFinishedGoingToSleep = true;
            return;
        }
        if (!mNotificationPanelViewController.canCameraGestureBeLaunched()) {
            if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Can't launch camera right now");
            return;
        }
        if (!mDeviceInteractive) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                    "com.android.systemui:CAMERA_GESTURE");
        }
        vibrateForCameraGesture();

        if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) {
            Log.v(TAG, "Camera launch");
            mKeyguardUpdateMonitor.onCameraLaunched();
        }

        if (!mStatusBarKeyguardViewManager.isShowing()) {
            final Intent cameraIntent = CameraIntents.getInsecureCameraIntent(mContext);
            startActivityDismissingKeyguard(cameraIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */);
        } else {
            if (!mDeviceInteractive) {
                // Avoid flickering of the scrim when we instant launch the camera and the bouncer
                // comes on.
                mGestureWakeLock.acquire(LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
            }
            if (isWakingUpOrAwake()) {
                if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Launching camera");
                if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                    mStatusBarKeyguardViewManager.reset(true /* hide */);
                }
                mNotificationPanelViewController.launchCamera(
                        mDeviceInteractive /* animate */, source);
                updateScrimController();
            } else {
                // We need to defer the camera launch until the screen comes on, since otherwise
                // we will dismiss us too early since we are waiting on an activity to be drawn and
                // incorrectly get notified because of the screen on event (which resumes and pauses
                // some activities)
                if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Deferring until screen turns on");
                mLaunchCameraWhenFinishedWaking = true;
            }
        }
    }

    @Override
    public void onEmergencyActionLaunchGestureDetected() {
        Intent emergencyIntent = getEmergencyActionIntent();

        if (emergencyIntent == null) {
            Log.wtf(TAG, "Couldn't find an app to process the emergency intent.");
            return;
        }

        if (isGoingToSleep()) {
            mLaunchEmergencyActionOnFinishedGoingToSleep = true;
            return;
        }

        if (!mDeviceInteractive) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_GESTURE,
                    "com.android.systemui:EMERGENCY_GESTURE");
        }
        // TODO(b/169087248) Possibly add haptics here for emergency action. Currently disabled for
        // app-side haptic experimentation.

        if (!mStatusBarKeyguardViewManager.isShowing()) {
            startActivityDismissingKeyguard(emergencyIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */);
            return;
        }

        if (!mDeviceInteractive) {
            // Avoid flickering of the scrim when we instant launch the camera and the bouncer
            // comes on.
            mGestureWakeLock.acquire(LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
        }

        if (isWakingUpOrAwake()) {
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.reset(true /* hide */);
            }
            mContext.startActivityAsUser(emergencyIntent, UserHandle.CURRENT);
            return;
        }
        // We need to defer the emergency action launch until the screen comes on, since otherwise
        // we will dismiss us too early since we are waiting on an activity to be drawn and
        // incorrectly get notified because of the screen on event (which resumes and pauses
        // some activities)
        mLaunchEmergencyActionWhenFinishedWaking = true;
    }

    private @Nullable Intent getEmergencyActionIntent() {
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

    private boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    private boolean isWakingUpOrAwake() {
        return mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_AWAKE
                || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_WAKING;
    }

    public void notifyBiometricAuthModeChanged() {
        mDozeServiceHost.updateDozing();
        updateScrimController();
    }

    /**
     * Set the location of the sensor on UDFPS if existent.
     */
    public void setSensorRect(RectF rect) {
        final float startRadius = (rect.right - rect.left) / 2f;
        mCircleReveal = new CircleReveal(rect.centerX(), rect.centerY(),
                startRadius, rect.centerY() - startRadius);
    }

    @VisibleForTesting
    public void updateScrimController() {
        Trace.beginSection("StatusBar#updateScrimController");

        // We don't want to end up in KEYGUARD state when we're unlocking with
        // fingerprint from doze. We should cross fade directly from black.
        boolean unlocking = mBiometricUnlockController.isWakeAndUnlock()
                || mKeyguardStateController.isKeyguardFadingAway();

        // Do not animate the scrim expansion when triggered by the fingerprint sensor.
        mScrimController.setExpansionAffectsAlpha(
                !mBiometricUnlockController.isBiometricUnlock());

        boolean launchingAffordanceWithPreview =
                mNotificationPanelViewController.isLaunchingAffordanceWithPreview();
        mScrimController.setLaunchingAffordanceWithPreview(launchingAffordanceWithPreview);

        if (mStatusBarKeyguardViewManager.isShowingAlternateAuth()) {
            mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED);
        } else if (mBouncerShowing) {
            // Bouncer needs the front scrim when it's on top of an activity,
            // tapping on a notification, editing QS or being dismissed by
            // FLAG_DISMISS_KEYGUARD_ACTIVITY.
            ScrimState state = mStatusBarKeyguardViewManager.bouncerNeedsScrimming()
                    ? ScrimState.BOUNCER_SCRIMMED : ScrimState.BOUNCER;
            mScrimController.transitionTo(state);
        } else if (isInLaunchTransition()
                || mLaunchCameraWhenFinishedWaking
                || launchingAffordanceWithPreview) {
            // TODO(b/170133395) Investigate whether Emergency Gesture flag should be included here.
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
        } else if (mIsKeyguard && !unlocking) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
        } else if (mBubblesOptional.isPresent() && mBubblesOptional.get().isStackExpanded()) {
            mScrimController.transitionTo(ScrimState.BUBBLE_EXPANDED, mUnlockScrimCallback);
        } else {
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        }
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
        return mStatusBarStateController.isDozing()
                && mDozeServiceHost.getIgnoreTouchWhilePulsing();
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

    public void setNotificationSnoozed(StatusBarNotification sbn, int hoursToSnooze) {
        mNotificationsController.setNotificationSnoozed(sbn, hoursToSnooze);
    }

    @Override
    public void toggleSplitScreen() {
        toggleSplitScreenMode(-1 /* metricsDockAction */, -1 /* metricsUndockAction */);
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

    @Override
    public void preloadRecentApps() {
        int msg = MSG_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void cancelPreloadRecentApps() {
        int msg = MSG_CANCEL_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void dismissKeyboardShortcutsMenu() {
        int msg = MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void toggleKeyboardShortcutsMenu(int deviceId) {
        int msg = MSG_TOGGLE_KEYBOARD_SHORTCUTS_MENU;
        mHandler.removeMessages(msg);
        mHandler.obtainMessage(msg, deviceId, 0).sendToTarget();
    }

    @Override
    public void setTopAppHidesStatusBar(boolean topAppHidesStatusBar) {
        mTopHidesStatusBar = topAppHidesStatusBar;
        if (!topAppHidesStatusBar && mWereIconsJustHidden) {
            // Immediately update the icon hidden state, since that should only apply if we're
            // staying fullscreen.
            mWereIconsJustHidden = false;
            mCommandQueue.recomputeDisableFlags(mDisplayId, true);
        }
        updateHideIconsForBouncer(true /* animate */);
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
     */
    private void executeActionDismissingKeyguard(Runnable action, boolean afterKeyguardGone,
            boolean collapsePanel) {
        if (!mDeviceProvisionedController.isDeviceProvisioned()) return;

        dismissKeyguardThenExecute(() -> {
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

            boolean deferred = collapsePanel ? mShadeController.collapsePanel() : false;
            return deferred;
        }, afterKeyguardGone);
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
        final boolean afterKeyguardGone = intent.isActivity()
                && mActivityIntentHelper.wouldLaunchResolverActivity(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());

        boolean collapse = animationController == null;
        executeActionDismissingKeyguard(() -> {
            try {
                // We wrap animationCallback with a StatusBarLaunchAnimatorController so that the
                // shade is collapsed after the animation (or when it is cancelled, aborted, etc).
                ActivityLaunchAnimator.Controller controller =
                        animationController != null ? new StatusBarLaunchAnimatorController(
                                animationController, this, intent.isActivity()) : null;

                mActivityLaunchAnimator.startPendingIntentWithAnimation(
                        controller, areLaunchAnimationsEnabled(),
                        (animationAdapter) -> intent.sendAndReturnResult(null, 0, null, null, null,
                                null, getActivityOptions(mDisplayId, animationAdapter)));
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
        }, afterKeyguardGone, collapse);
    }

    private void postOnUiThread(Runnable runnable) {
        mMainThreadHandler.post(runnable);
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
            options = ActivityOptions.makeRemoteAnimation(animationAdapter);
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

    protected void notifyHeadsUpGoingToSleep() {
        maybeEscalateHeadsUp();
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

    @Override
    public void showAssistDisclosure() {
        mAssistManagerLazy.get().showDisclosure();
    }

    public NotificationPanelViewController getPanelController() {
        return mNotificationPanelViewController;
    }

    @Override
    public void startAssist(Bundle args) {
        mAssistManagerLazy.get().startAssist(args);
    }
    // End Extra BaseStatusBarMethods.

    public NotificationGutsManager getGutsManager() {
        return mGutsManager;
    }

    private boolean isTransientShown() {
        return mTransientShown;
    }

    @Override
    public void suppressAmbientDisplay(boolean suppressed) {
        mDozeServiceHost.setDozeSuppressed(suppressed);
    }

    public void addExpansionChangedListener(@NonNull ExpansionChangedListener listener) {
        mExpansionChangedListeners.add(listener);
    }

    public void removeExpansionChangedListener(@NonNull ExpansionChangedListener listener) {
        mExpansionChangedListeners.remove(listener);
    }

    private void updateLightRevealScrimVisibility() {
        if (mLightRevealScrim == null) {
            // status bar may not be inflated yet
            return;
        }

        if (mFeatureFlags.useNewLockscreenAnimations()
                && (mDozeParameters.getAlwaysOn() || mDozeParameters.isQuickPickupEnabled())) {
            mLightRevealScrim.setVisibility(View.VISIBLE);
            mLightRevealScrim.setRevealEffect(LiftReveal.INSTANCE);
        } else {
            mLightRevealScrim.setVisibility(View.GONE);
        }
    }
}
