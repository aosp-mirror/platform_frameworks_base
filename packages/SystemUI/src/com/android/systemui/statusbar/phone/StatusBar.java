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

import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowType;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;
import static com.android.systemui.shared.system.WindowManagerWrapper.NAV_BAR_POS_INVALID;
import static com.android.systemui.shared.system.WindowManagerWrapper.NAV_BAR_POS_LEFT;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.PERMISSION_SELF;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_WARNING;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.DateTimeView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.EventLogTags;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.InitController;
import com.android.systemui.Interpolators;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.charging.WirelessChargingAnimation;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.fragments.ExtensionFragmentListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.NotificationListController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.UnlockMethodCache.OnUnlockMethodChangedListener;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.PreviewInflater;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;
import com.android.systemui.volume.VolumeComponent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Map;

import javax.inject.Inject;

import dagger.Subcomponent;

public class StatusBar extends SystemUI implements DemoMode,
        ActivityStarter, OnUnlockMethodChangedListener,
        OnHeadsUpChangedListener, CommandQueue.Callbacks, ZenModeController.Callback,
        ColorExtractor.OnColorsChangedListener, ConfigurationListener,
        StatusBarStateController.StateListener, ShadeController,
        ActivityLaunchAnimator.Callback, AmbientPulseManager.OnAmbientChangedListener,
        AppOpsController.Callback {
    public static final boolean MULTIUSER_DEBUG = false;

    public static final boolean ENABLE_CHILD_NOTIFICATIONS
            = SystemProperties.getBoolean("debug.child_notifs", true);

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

    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState;
    protected boolean mBouncerShowing;

    private PhoneStatusBarPolicy mIconPolicy;
    private StatusBarSignalPolicy mSignalPolicy;

    private VolumeComponent mVolumeComponent;
    private BrightnessMirrorController mBrightnessMirrorController;
    private boolean mBrightnessMirrorVisible;
    protected BiometricUnlockController mBiometricUnlockController;
    private LightBarController mLightBarController;
    protected LockscreenWallpaper mLockscreenWallpaper;
    @VisibleForTesting
    protected AutoHideController mAutoHideController;

    private int mNaturalBarHeight = -1;

    private final Point mCurrentDisplaySize = new Point();

    protected StatusBarWindowView mStatusBarWindow;
    protected PhoneStatusBarView mStatusBarView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    protected StatusBarWindowController mStatusBarWindowController;
    protected UnlockMethodCache mUnlockMethodCache;
    @VisibleForTesting
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @VisibleForTesting
    DozeServiceHost mDozeServiceHost = new DozeServiceHost();
    private boolean mWakeUpComingFromTouch;
    private PointF mWakeUpTouchLocation;

    private final Object mQueueLock = new Object();

    protected StatusBarIconController mIconController;
    @Inject
    InjectionInflationController mInjectionInflater;
    @Inject
    PulseExpansionHandler mPulseExpansionHandler;
    @Inject
    NotificationWakeUpCoordinator mWakeUpCoordinator;

    // expanded notifications
    protected NotificationPanelView mNotificationPanel; // the sliding/resizing panel within the notification window

    // settings
    private QSPanel mQSPanel;

    KeyguardIndicationController mKeyguardIndicationController;

    // RemoteInputView to be activated after unlock
    private View mPendingRemoteInputView;

    private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler =
            Dependency.get(RemoteInputQuickSettingsDisabler.class);

    private View mReportRejectedTouch;

    private boolean mExpandedVisible;

    private final int[] mAbsPos = new int[2];
    private final ArrayList<Runnable> mPostCollapseRunnables = new ArrayList<>();

    private NotificationGutsManager mGutsManager;
    protected NotificationLogger mNotificationLogger;
    protected NotificationEntryManager mEntryManager;
    private NotificationListController mNotificationListController;
    private NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    protected NotificationViewHierarchyManager mViewHierarchyManager;
    protected ForegroundServiceController mForegroundServiceController;
    protected AppOpsController mAppOpsController;
    protected KeyguardViewMediator mKeyguardViewMediator;
    private ZenModeController mZenController;
    private final NotificationAlertingManager mNotificationAlertingManager =
            Dependency.get(NotificationAlertingManager.class);

    // for disabling the status bar
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    // tracking calls to View.setSystemUiVisibility()
    private int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;
    private final Rect mLastFullscreenStackBounds = new Rect();
    private final Rect mLastDockedStackBounds = new Rect();

    private final DisplayMetrics mDisplayMetrics = Dependency.get(DisplayMetrics.class);

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private ScreenPinningRequest mScreenPinningRequest;

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    @VisibleForTesting
    protected boolean mUserSetup = false;
    private final DeviceProvisionedListener mUserSetupObserver = new DeviceProvisionedListener() {
        @Override
        public void onUserSetupChanged() {
            final boolean userSetup = mDeviceProvisionedController.isUserSetup(
                    mDeviceProvisionedController.getCurrentUser());
            if (MULTIUSER_DEBUG) {
                Log.d(TAG, String.format("User setup changed: userSetup=%s mUserSetup=%s",
                        userSetup, mUserSetup));
            }

            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mStatusBarView != null)
                    animateCollapseQuickSettings();
                if (mNotificationPanel != null) {
                    mNotificationPanel.setUserSetupComplete(mUserSetup);
                }
                updateQsExpansionEnabled();
            }
        }
    };

    protected final H mHandler = createHandler();

    private int mInteractingWindows;
    private @TransitionMode int mStatusBarMode;

    private ViewMediatorCallback mKeyguardViewMediatorCallback;
    protected ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    protected boolean mDozing;
    private boolean mDozingRequested;

    private NotificationMediaManager mMediaManager;
    protected NotificationLockscreenUserManager mLockscreenUserManager;
    protected NotificationRemoteInputManager mRemoteInputManager;

    private final BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
            if (wallpaperManager == null) {
                Log.w(TAG, "WallpaperManager not available");
                return;
            }
            WallpaperInfo info = wallpaperManager.getWallpaperInfo(UserHandle.USER_CURRENT);
            final boolean deviceSupportsAodWallpaper = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_dozeSupportsAodWallpaper);
            // If WallpaperInfo is null, it must be ImageWallpaper.
            final boolean supportsAmbientMode = deviceSupportsAodWallpaper
                    && (info == null || info.supportsAmbientMode());

            mStatusBarWindowController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mScrimController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
        }
    };

    private Runnable mLaunchTransitionEndRunnable;
    private NotificationEntry mDraggedDownEntry;
    private boolean mLaunchCameraOnScreenTurningOn;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
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
            if (mKeyguardMonitor.isKeyguardFadingAway()) {
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }
        }

        @Override
        public void onCancelled() {
            onFinished();
        }
    };

    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    protected UserSwitcherController mUserSwitcherController;
    private NetworkController mNetworkController;
    private KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
    private BatteryController mBatteryController;
    protected boolean mPanelExpanded;
    private UiModeManager mUiModeManager;
    protected boolean mIsKeyguard;
    private LogMaker mStatusBarStateLog;
    protected NotificationIconAreaController mNotificationIconAreaController;
    @Nullable private View mAmbientIndicationContainer;
    private SysuiColorExtractor mColorExtractor;
    private ScreenLifecycle mScreenLifecycle;
    @VisibleForTesting WakefulnessLifecycle mWakefulnessLifecycle;

    private final View.OnClickListener mGoToLockedShadeListener = v -> {
        if (mState == StatusBarState.KEYGUARD) {
            wakeUpIfDozing(SystemClock.uptimeMillis(), v, "SHADE_CLICK");
            goToLockedShade(null);
        }
    };
    private boolean mNoAnimationOnNextBarModeChange;
    protected FalsingManager mFalsingManager;
    private final SysuiStatusBarStateController mStatusBarStateController =
            (SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class);

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
                    if (dreaming) {
                        maybeEscalateHeadsUp();
                    }
                }

                @Override
                public void onStrongAuthStateChanged(int userId) {
                    super.onStrongAuthStateChanged(userId);
                    mEntryManager.updateNotifications();
                }
            };
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private boolean mVibrateOnOpening;
    private VibratorHelper mVibratorHelper;
    private ActivityLaunchAnimator mActivityLaunchAnimator;
    protected NotificationPresenter mPresenter;
    private NotificationActivityStarter mNotificationActivityStarter;
    private boolean mPulsing;
    protected BubbleController mBubbleController;
    private final BubbleController.BubbleExpandListener mBubbleExpandListener =
            (isExpanding, key) -> {
                mEntryManager.updateNotifications();
                updateScrimController();
            };

    @Override
    public void onActiveStateChanged(int code, int uid, String packageName, boolean active) {
        mForegroundServiceController.onAppOpChanged(code, uid, packageName, active);
        Dependency.get(Dependency.MAIN_HANDLER).post(() -> {
            mNotificationListController.updateNotificationsForAppOp(code, uid, packageName, active);
        });
    }

    protected static final int[] APP_OPS = new int[] {AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
            AppOpsManager.OP_RECORD_AUDIO,
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION};

    @Override
    public void start() {
        mGroupManager = Dependency.get(NotificationGroupManager.class);
        mGroupAlertTransferHelper = Dependency.get(NotificationGroupAlertTransferHelper.class);
        mVisualStabilityManager = Dependency.get(VisualStabilityManager.class);
        mNotificationLogger = Dependency.get(NotificationLogger.class);
        mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);
        mNotificationListener =  Dependency.get(NotificationListener.class);
        mNotificationListener.registerAsSystemService();
        mNetworkController = Dependency.get(NetworkController.class);
        mUserSwitcherController = Dependency.get(UserSwitcherController.class);
        mScreenLifecycle = Dependency.get(ScreenLifecycle.class);
        mScreenLifecycle.addObserver(mScreenObserver);
        mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mBatteryController = Dependency.get(BatteryController.class);
        mAssistManager = Dependency.get(AssistManager.class);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mLockscreenUserManager = Dependency.get(NotificationLockscreenUserManager.class);
        mGutsManager = Dependency.get(NotificationGutsManager.class);
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        mEntryManager = Dependency.get(NotificationEntryManager.class);
        mNotificationInterruptionStateProvider =
                Dependency.get(NotificationInterruptionStateProvider.class);
        mViewHierarchyManager = Dependency.get(NotificationViewHierarchyManager.class);
        mForegroundServiceController = Dependency.get(ForegroundServiceController.class);
        mAppOpsController = Dependency.get(AppOpsController.class);
        mZenController = Dependency.get(ZenModeController.class);
        mKeyguardViewMediator = getComponent(KeyguardViewMediator.class);
        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
        mNavigationBarController = Dependency.get(NavigationBarController.class);
        mBubbleController = Dependency.get(BubbleController.class);
        mBubbleController.setExpandListener(mBubbleExpandListener);
        KeyguardSliceProvider sliceProvider = KeyguardSliceProvider.getAttachedInstance();
        if (sliceProvider != null) {
            sliceProvider.initDependencies(mMediaManager, mStatusBarStateController);
        } else {
            Log.w(TAG, "Cannot init KeyguardSliceProvider dependencies");
        }

        mColorExtractor.addOnColorsChangedListener(this);
        mStatusBarStateController.addCallback(this,
                SysuiStatusBarStateController.RANK_STATUS_BAR);

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayId = mDisplay.getDisplayId();
        updateDisplaySize();

        Resources res = mContext.getResources();
        mVibrateOnOpening = mContext.getResources().getBoolean(
                R.bool.config_vibrateOnIconAnimation);
        mVibratorHelper = Dependency.get(VibratorHelper.class);

        DateTimeView.setReceiverHandler(Dependency.get(Dependency.TIME_TICK_HANDLER));
        putComponent(StatusBar.class, this);

        // start old BaseStatusBar.start().
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mRecents = getComponent(Recents.class);

        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);

        // Connect in to the status bar manager service
        mCommandQueue = getComponent(CommandQueue.class);
        mCommandQueue.addCallback(this);

        int[] switches = new int[9];
        ArrayList<IBinder> binders = new ArrayList<>();
        ArrayList<String> iconSlots = new ArrayList<>();
        ArrayList<StatusBarIcon> icons = new ArrayList<>();
        Rect fullscreenStackBounds = new Rect();
        Rect dockedStackBounds = new Rect();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconSlots, icons, switches, binders,
                    fullscreenStackBounds, dockedStackBounds);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        createAndAddWindows();

        // Make sure we always have the most current wallpaper info.
        IntentFilter wallpaperChangedFilter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
        mContext.registerReceiverAsUser(mWallpaperChangedReceiver, UserHandle.ALL,
                wallpaperChangedFilter, null /* broadcastPermission */, null /* scheduler */);
        mWallpaperChangedReceiver.onReceive(mContext, null);

        // Set up the initial notification state. This needs to happen before CommandQueue.disable()
        setUpPresenter();

        setSystemUiVisibility(mDisplayId, switches[1], switches[7], switches[8], 0xffffffff,
                fullscreenStackBounds, dockedStackBounds);
        topAppWindowChanged(mDisplayId, switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(mDisplayId, binders.get(0), switches[3], switches[4], switches[5] != 0);

        // Set up the initial icon state
        int N = iconSlots.size();
        for (int i=0; i < N; i++) {
            mCommandQueue.setIcon(iconSlots.get(i), icons.get(i));
        }


        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x menu=0x%08x imeButton=0x%08x",
                   icons.size(),
                   switches[0],
                   switches[1],
                   switches[2],
                   switches[3]
                   ));
        }

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(BANNER_ACTION_CANCEL);
        internalFilter.addAction(BANNER_ACTION_SETUP);
        mContext.registerReceiver(mBannerActionBroadcastReceiver, internalFilter, PERMISSION_SELF,
                null);

        IWallpaperManager wallpaperManager = IWallpaperManager.Stub.asInterface(
                ServiceManager.getService(Context.WALLPAPER_SERVICE));
        try {
            wallpaperManager.setInAmbientMode(false /* ambientMode */, 0L /* duration */);
        } catch (RemoteException e) {
            // Just pass, nothing critical.
        }

        // end old BaseStatusBar.start().

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext, mIconController);
        mSignalPolicy = new StatusBarSignalPolicy(mContext, mIconController);

        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mUnlockMethodCache.addListener(this);
        startKeyguard();

        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
        putComponent(DozeHost.class, mDozeServiceHost);

        mScreenPinningRequest = new ScreenPinningRequest(mContext);
        mFalsingManager = FalsingManager.getInstance(mContext);

        Dependency.get(ActivityStarterDelegate.class).setActivityStarterImpl(this);

        Dependency.get(ConfigurationController.class).addCallback(this);

        // set the initial view visibility
        Dependency.get(InitController.class).addPostInitTask(this::updateAreThereNotifications);
        Dependency.get(InitController.class).addPostInitTask(() -> {
            setUpDisableFlags(switches[0], switches[6]);
        });

    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected void makeStatusBarView() {
        final Context context = mContext;
        updateDisplaySize(); // populates mDisplayMetrics
        updateResources();
        updateTheme();

        inflateStatusBarWindow(context);
        mStatusBarWindow.setService(this);
        mStatusBarWindow.setOnTouchListener(getStatusBarWindowTouchListener());

        // TODO: Deal with the ugliness that comes from having some of the statusbar broken out
        // into fragments, but the rest here, it leaves some awkward lifecycle and whatnot.
        mNotificationPanel = mStatusBarWindow.findViewById(R.id.notification_panel);
        mStackScroller = mStatusBarWindow.findViewById(R.id.notification_stack_scroller);
        mZenController.addCallback(this);
        NotificationListContainer notifListContainer = (NotificationListContainer) mStackScroller;
        mNotificationLogger.setUpWithContainer(notifListContainer);

        mNotificationIconAreaController = SystemUIFactory.getInstance()
                .createNotificationIconAreaController(context, this,
                        mStatusBarStateController, mNotificationListener);
        inflateShelf();
        mNotificationIconAreaController.setupShelf(mNotificationShelf);

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mNotificationIconAreaController);
        // Allow plugins to reference DarkIconDispatcher and StatusBarStateController
        Dependency.get(PluginDependencyProvider.class)
                .allowPluginDependency(DarkIconDispatcher.class);
        Dependency.get(PluginDependencyProvider.class)
                .allowPluginDependency(StatusBarStateController.class);
        FragmentHostManager.get(mStatusBarWindow)
                .addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
                    CollapsedStatusBarFragment statusBarFragment =
                            (CollapsedStatusBarFragment) fragment;
                    statusBarFragment.initNotificationIconArea(mNotificationIconAreaController);
                    PhoneStatusBarView oldStatusBarView = mStatusBarView;
                    mStatusBarView = (PhoneStatusBarView) fragment.getView();
                    mStatusBarView.setBar(this);
                    mStatusBarView.setPanel(mNotificationPanel);
                    mStatusBarView.setScrimController(mScrimController);

                    // CollapsedStatusBarFragment re-inflated PhoneStatusBarView and both of
                    // mStatusBarView.mExpanded and mStatusBarView.mBouncerShowing are false.
                    // PhoneStatusBarView's new instance will set to be gone in
                    // PanelBar.updateVisibility after calling mStatusBarView.setBouncerShowing
                    // that will trigger PanelBar.updateVisibility. If there is a heads up showing,
                    // it needs to notify PhoneStatusBarView's new instance to update the correct
                    // status by calling mNotificationPanel.notifyBarPanelExpansionChanged().
                    if (mHeadsUpManager.hasPinnedHeadsUp()) {
                        mNotificationPanel.notifyBarPanelExpansionChanged();
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
                    mHeadsUpAppearanceController = new HeadsUpAppearanceController(
                            mNotificationIconAreaController, mHeadsUpManager, mStatusBarWindow);
                    mHeadsUpAppearanceController.readFrom(oldController);
                    mStatusBarWindow.setStatusBarView(mStatusBarView);
                    updateAreThereNotifications();
                    checkBarModes();
                }).getFragmentManager()
                .beginTransaction()
                .replace(R.id.status_bar_container, new CollapsedStatusBarFragment(),
                        CollapsedStatusBarFragment.TAG)
                .commit();
        mIconController = Dependency.get(StatusBarIconController.class);

        mHeadsUpManager = new HeadsUpManagerPhone(context, mStatusBarWindow, mGroupManager, this,
                mVisualStabilityManager);
        Dependency.get(ConfigurationController.class).addCallback(mHeadsUpManager);
        mHeadsUpManager.addListener(this);
        mHeadsUpManager.addListener(mNotificationPanel);
        mHeadsUpManager.addListener(mGroupManager);
        mHeadsUpManager.addListener(mGroupAlertTransferHelper);
        mHeadsUpManager.addListener(mVisualStabilityManager);
        mAmbientPulseManager.addListener(this);
        mAmbientPulseManager.addListener(mGroupManager);
        mAmbientPulseManager.addListener(mGroupAlertTransferHelper);
        mNotificationPanel.setHeadsUpManager(mHeadsUpManager);
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
        mGroupAlertTransferHelper.setHeadsUpManager(mHeadsUpManager);
        mNotificationLogger.setHeadsUpManager(mHeadsUpManager);
        putComponent(HeadsUpManager.class, mHeadsUpManager);

        createNavigationBar();

        if (ENABLE_LOCKSCREEN_WALLPAPER) {
            mLockscreenWallpaper = new LockscreenWallpaper(mContext, this, mHandler);
        }

        mKeyguardIndicationController =
                SystemUIFactory.getInstance().createKeyguardIndicationController(mContext,
                        mStatusBarWindow.findViewById(R.id.keyguard_indication_area),
                        mNotificationPanel.getLockIcon());
        mNotificationPanel.setKeyguardIndicationController(mKeyguardIndicationController);

        mAmbientIndicationContainer = mStatusBarWindow.findViewById(
                R.id.ambient_indication_container);

        // TODO: Find better place for this callback.
        mBatteryController.addCallback(new BatteryStateChangeCallback() {
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
        });

        mAutoHideController = Dependency.get(AutoHideController.class);
        mAutoHideController.setStatusBar(this);

        mLightBarController = Dependency.get(LightBarController.class);

        ScrimView scrimBehind = mStatusBarWindow.findViewById(R.id.scrim_behind);
        ScrimView scrimInFront = mStatusBarWindow.findViewById(R.id.scrim_in_front);
        mScrimController = SystemUIFactory.getInstance().createScrimController(
                scrimBehind, scrimInFront, mLockscreenWallpaper,
                (state, alpha, color) -> mLightBarController.setScrimState(state, alpha, color),
                scrimsVisible -> {
                    if (mStatusBarWindowController != null) {
                        mStatusBarWindowController.setScrimsVisibility(scrimsVisible);
                    }
                }, DozeParameters.getInstance(mContext),
                mContext.getSystemService(AlarmManager.class));
        mNotificationPanel.initDependencies(this, mGroupManager, mNotificationShelf,
                mHeadsUpManager, mNotificationIconAreaController, mScrimController);
        mDozeScrimController = new DozeScrimController(DozeParameters.getInstance(context));

        BackDropView backdrop = mStatusBarWindow.findViewById(R.id.backdrop);
        mMediaManager.setup(backdrop, backdrop.findViewById(R.id.backdrop_front),
                backdrop.findViewById(R.id.backdrop_back), mScrimController, mLockscreenWallpaper);

        // Other icons
        mVolumeComponent = getComponent(VolumeComponent.class);

        mNotificationPanel.setUserSetupComplete(mUserSetup);
        if (UserManager.get(mContext).isUserSwitcherEnabled()) {
            createUserSwitcher();
        }

        // Set up the quick settings tile panel
        View container = mStatusBarWindow.findViewById(R.id.qs_frame);
        if (container != null) {
            FragmentHostManager fragmentHostManager = FragmentHostManager.get(container);
            ExtensionFragmentListener.attachExtensonToFragment(container, QS.TAG, R.id.qs_frame,
                    Dependency.get(ExtensionController.class)
                            .newExtension(QS.class)
                            .withPlugin(QS.class)
                            .withDefault(this::createDefaultQSFragment)
                            .build());
            mBrightnessMirrorController = new BrightnessMirrorController(mStatusBarWindow,
                    (visible) -> {
                        mBrightnessMirrorVisible = visible;
                        updateScrimController();
                    });
            fragmentHostManager.addTagListener(QS.TAG, (tag, f) -> {
                QS qs = (QS) f;
                if (qs instanceof QSFragment) {
                    mQSPanel = ((QSFragment) qs).getQsPanel();
                    mQSPanel.setBrightnessMirror(mBrightnessMirrorController);
                }
            });
        }

        mReportRejectedTouch = mStatusBarWindow.findViewById(R.id.report_rejected_touch);
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

                PrintWriter falsingPw = new PrintWriter(message);
                FalsingLog.dump(falsingPw);
                falsingPw.flush();

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

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (!pm.isScreenOn()) {
            mBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        }
        mGestureWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "GestureWakeLock");
        mVibrator = mContext.getSystemService(Vibrator.class);
        int[] pattern = mContext.getResources().getIntArray(
                R.array.config_cameraLaunchGestureVibePattern);
        mCameraLaunchGestureVibePattern = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            mCameraLaunchGestureVibePattern[i] = pattern[i];
        }

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);

        IntentFilter demoFilter = new IntentFilter();
        if (DEBUG_MEDIA_FAKE_ARTWORK) {
            demoFilter.addAction(ACTION_FAKE_ARTWORK);
        }
        demoFilter.addAction(ACTION_DEMO);
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

    protected QS createDefaultQSFragment() {
        return FragmentHostManager.get(mStatusBarWindow).create(QSFragment.class);
    }

    private void setUpPresenter() {
        // Set up the initial notification state.
        mActivityLaunchAnimator = new ActivityLaunchAnimator(
                mStatusBarWindow, this, mNotificationPanel,
                (NotificationListContainer) mStackScroller);

        final NotificationRowBinderImpl rowBinder =
                new NotificationRowBinderImpl(
                        mContext,
                        SystemUIFactory.getInstance().provideAllowNotificationLongPress());

        mPresenter = new StatusBarNotificationPresenter(mContext, mNotificationPanel,
                mHeadsUpManager, mStatusBarWindow, mStackScroller, mDozeScrimController,
                mScrimController, mActivityLaunchAnimator, mStatusBarKeyguardViewManager,
                mNotificationAlertingManager, rowBinder);

        mNotificationListController =
                new NotificationListController(
                        mEntryManager,
                        (NotificationListContainer) mStackScroller,
                        mForegroundServiceController,
                        mDeviceProvisionedController);

        mAppOpsController.addCallback(APP_OPS, this);
        mNotificationShelf.setOnActivatedListener(mPresenter);
        mRemoteInputManager.getController().addCallback(mStatusBarWindowController);

        final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback =
                (StatusBarRemoteInputCallback) Dependency.get(
                        NotificationRemoteInputManager.Callback.class);
        final ShadeController shadeController = Dependency.get(ShadeController.class);
        final ActivityStarter activityStarter = Dependency.get(ActivityStarter.class);

        mNotificationActivityStarter = new StatusBarNotificationActivityStarter(mContext,
                mCommandQueue, mAssistManager, mNotificationPanel, mPresenter, mEntryManager,
                mHeadsUpManager, activityStarter, mActivityLaunchAnimator,
                mBarService, mStatusBarStateController, mKeyguardManager, mDreamManager,
                mRemoteInputManager, mStatusBarRemoteInputCallback, mGroupManager,
                mLockscreenUserManager, shadeController, mKeyguardMonitor,
                mNotificationInterruptionStateProvider, mMetricsLogger,
                new LockPatternUtils(mContext));

        mGutsManager.setNotificationActivityStarter(mNotificationActivityStarter);

        mEntryManager.setRowBinder(rowBinder);
        rowBinder.setNotificationClicker(new NotificationClicker(
                this, Dependency.get(BubbleController.class), mNotificationActivityStarter));

        mGroupAlertTransferHelper.bind(mEntryManager, mGroupManager);
        mNotificationListController.bind();
    }

    /**
     * Post-init task of {@link #start()}
     * @param state1 disable1 flags
     * @param state2 disable2 flags
     */
    protected void setUpDisableFlags(int state1, int state2) {
        mCommandQueue.disable(mDisplayId, state1, state2, false /* animate */);
    }

    @Override
    public void addAfterKeyguardGoneRunnable(Runnable runnable) {
        mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
    }

    @Override
    public boolean isDozing() {
        return mDozing;
    }

    @Override
    public void wakeUpIfDozing(long time, View where, String why) {
        if (mDozing) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            pm.wakeUp(time, PowerManager.WAKE_REASON_GESTURE, "com.android.systemui:" + why);
            mWakeUpComingFromTouch = true;
            where.getLocationInWindow(mTmpInt2);
            mWakeUpTouchLocation = new PointF(mTmpInt2[0] + where.getWidth() / 2,
                    mTmpInt2[1] + where.getHeight() / 2);
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
            mFalsingManager.onScreenOnFromTouch();
        }
    }

    // TODO(b/117478341): This was left such that CarStatusBar can override this method.
    // Try to remove this.
    protected void createNavigationBar() {
        mNavigationBarController.createNavigationBars(true /* includeDefaultDisplay */);
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
                    animateCollapsePanels();
                }
            }
            return mStatusBarWindow.onTouchEvent(event);
        };
    }

    private void inflateShelf() {
        mNotificationShelf =
                (NotificationShelf) LayoutInflater.from(mContext).inflate(
                        R.layout.status_bar_notification_shelf, mStackScroller, false);
        mNotificationShelf.setOnClickListener(mGoToLockedShadeListener);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        // TODO: Remove this.
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onDensityOrFontScaleChanged();
        }
        mStatusBarKeyguardViewManager.onDensityOrFontScaleChanged();
        // TODO: Bring these out of StatusBar.
        ((UserInfoControllerImpl) Dependency.get(UserInfoController.class))
                .onDensityOrFontScaleChanged();
        Dependency.get(UserSwitcherController.class).onDensityOrFontScaleChanged();
        if (mKeyguardUserSwitcher != null) {
            mKeyguardUserSwitcher.onDensityOrFontScaleChanged();
        }
        mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
        mHeadsUpManager.onDensityOrFontScaleChanged();
    }

    @Override
    public void onThemeChanged() {
        // Recreate Indication controller because internal references changed
        if (mKeyguardIndicationController != null) {
            mKeyguardIndicationController.destroy();
        }
        mKeyguardIndicationController =
                SystemUIFactory.getInstance().createKeyguardIndicationController(mContext,
                        mStatusBarWindow.findViewById(R.id.keyguard_indication_area),
                        mNotificationPanel.getLockIcon());
        mNotificationPanel.setKeyguardIndicationController(mKeyguardIndicationController);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mKeyguardIndicationController.setVisible(mState == StatusBarState.KEYGUARD);
        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.onThemeChanged();
        }
        if (mAmbientIndicationContainer instanceof AutoReinflateContainer) {
            ((AutoReinflateContainer) mAmbientIndicationContainer).inflateLayout();
        }
    }

    @Override
    public void onOverlayChanged() {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onOverlayChanged();
        }
        // We need the new R.id.keyguard_indication_area before recreating
        // mKeyguardIndicationController
        mNotificationPanel.onThemeChanged();
        onThemeChanged();
    }

    @Override
    public void onUiModeChanged() {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onUiModeChanged();
        }
    }

    protected void createUserSwitcher() {
        mKeyguardUserSwitcher = new KeyguardUserSwitcher(mContext,
                mStatusBarWindow.findViewById(R.id.keyguard_user_switcher),
                mStatusBarWindow.findViewById(R.id.keyguard_header),
                mNotificationPanel);
    }

    protected void inflateStatusBarWindow(Context context) {
        mStatusBarWindow = (StatusBarWindowView) mInjectionInflater.injectable(
                LayoutInflater.from(context)).inflate(R.layout.super_status_bar, null);
    }

    protected void startKeyguard() {
        Trace.beginSection("StatusBar#startKeyguard");
        KeyguardViewMediator keyguardViewMediator = getComponent(KeyguardViewMediator.class);
        mBiometricUnlockController = new BiometricUnlockController(mContext,
                mDozeScrimController, keyguardViewMediator,
                mScrimController, this, UnlockMethodCache.getInstance(mContext),
                new Handler(), mKeyguardUpdateMonitor, Dependency.get(TunerService.class));
        mStatusBarKeyguardViewManager = keyguardViewMediator.registerStatusBar(this,
                getBouncerContainer(), mNotificationPanel, mBiometricUnlockController);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mRemoteInputManager.getController().addCallback(mStatusBarKeyguardViewManager);

        mKeyguardViewMediatorCallback = keyguardViewMediator.getViewMediatorCallback();
        mLightBarController.setBiometricUnlockController(mBiometricUnlockController);
        mMediaManager.setBiometricUnlockController(mBiometricUnlockController);
        mNotificationPanel.setBouncer(mStatusBarKeyguardViewManager.getBouncer());
        Dependency.get(KeyguardDismissUtil.class).setDismissHandler(this::executeWhenUnlocked);
        Trace.endSection();
    }

    protected View getStatusBarView() {
        return mStatusBarView;
    }

    public StatusBarWindowView getStatusBarWindow() {
        return mStatusBarWindow;
    }

    protected ViewGroup getBouncerContainer() {
        return mStatusBarWindow;
    }

    public int getStatusBarHeight() {
        if (mNaturalBarHeight < 0) {
            final Resources res = mContext.getResources();
            mNaturalBarHeight =
                    res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        }
        return mNaturalBarHeight;
    }

    protected boolean toggleSplitScreenMode(int metricsDockAction, int metricsUndockAction) {
        if (mRecents == null) {
            return false;
        }
        int dockSide = WindowManagerProxy.getInstance().getDockSide();
        if (dockSide == WindowManager.DOCKED_INVALID) {
            final int navbarPos = WindowManagerWrapper.getInstance().getNavBarPosition(mDisplayId);
            if (navbarPos == NAV_BAR_POS_INVALID) {
                return false;
            }
            int createMode = navbarPos == NAV_BAR_POS_LEFT
                    ? SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT
                    : SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
            return mRecents.splitPrimaryTask(createMode, null, metricsDockAction);
        } else {
            Divider divider = getComponent(Divider.class);
            if (divider != null) {
                if (divider.isMinimized() && !divider.isHomeStackResizable()) {
                    // Undocking from the minimized state is not supported
                    return false;
                } else {
                    divider.onUndockingTask();
                    if (metricsUndockAction != -1) {
                        mMetricsLogger.action(metricsUndockAction);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Disable QS if device not provisioned.
     * If the user switcher is simple then disable QS during setup because
     * the user intends to use the lock screen user switcher, QS in not needed.
     */
    private void updateQsExpansionEnabled() {
        mNotificationPanel.setQsExpansionEnabled(mDeviceProvisionedController.isDeviceProvisioned()
                && (mUserSetup || mUserSwitcherController == null
                        || !mUserSwitcherController.isSimpleUserSwitcher())
                && ((mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) == 0)
                && ((mDisabled2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) == 0)
                && !mDozing
                && !ONLY_CORE_APPS);
    }

    public void addQsTile(ComponentName tile) {
        if (mQSPanel != null && mQSPanel.getHost() != null) {
            mQSPanel.getHost().addTile(tile);
        }
    }

    public void remQsTile(ComponentName tile) {
        if (mQSPanel != null && mQSPanel.getHost() != null) {
            mQSPanel.getHost().removeTile(tile);
        }
    }

    public void clickTile(ComponentName tile) {
        mQSPanel.clickTile(tile);
    }

    public boolean areNotificationsHidden() {
        return mZenController.areNotificationsHiddenInShade();
    }

    public void requestNotificationUpdate() {
        mEntryManager.updateNotifications();
    }

    /**
     * Asks {@link KeyguardUpdateMonitor} to run face auth.
     */
    public void requestFaceAuth() {
        if (!mUnlockMethodCache.canSkipBouncer()) {
            mKeyguardUpdateMonitor.requestFaceAuth();
        }
    }

    public void updateAreThereNotifications() {
        if (SPEW) {
            final boolean clearable = hasActiveNotifications() &&
                    mNotificationPanel.hasActiveClearableNotifications();
            Log.d(TAG, "updateAreThereNotifications: N=" +
                    mEntryManager.getNotificationData().getActiveNotifications().size() + " any=" +
                    hasActiveNotifications() + " clearable=" + clearable);
        }

        if (mStatusBarView != null) {
            final View nlo = mStatusBarView.findViewById(R.id.notification_lights_out);
            final boolean showDot = hasActiveNotifications() && !areLightsOn();
            if (showDot != (nlo.getAlpha() == 1.0f)) {
                if (showDot) {
                    nlo.setAlpha(0f);
                    nlo.setVisibility(View.VISIBLE);
                }
                nlo.animate()
                        .alpha(showDot ? 1 : 0)
                        .setDuration(showDot ? 750 : 250)
                        .setInterpolator(new AccelerateInterpolator(2.0f))
                        .setListener(showDot ? null : new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator _a) {
                                nlo.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }
        mMediaManager.findAndUpdateMediaNotifications();
    }

    private void updateReportRejectedTouchVisibility() {
        if (mReportRejectedTouch == null) {
            return;
        }
        mReportRejectedTouch.setVisibility(mState == StatusBarState.KEYGUARD && !mDozing
                && mFalsingManager.isReportingEnabled() ? View.VISIBLE : View.INVISIBLE);
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
                animateCollapsePanels();
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
            mNotificationInterruptionStateProvider.setDisableNotificationAlerts(
                    (state1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0);
        }

        if ((diff2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) {
            updateQsExpansionEnabled();
        }

        if ((diff2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
            updateQsExpansionEnabled();
            if ((state1 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
                animateCollapsePanels();
            }
        }
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
        startActivityDismissingKeyguard(intent, false, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        startActivityDismissingKeyguard(intent, false, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, callback, 0);
    }

    public void setQsExpanded(boolean expanded) {
        mStatusBarWindowController.setQsExpanded(expanded);
        mNotificationPanel.setStatusAccessibilityImportance(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public boolean isWakeUpComingFromTouch() {
        return mWakeUpComingFromTouch;
    }

    public boolean isFalsingThresholdNeeded() {
        return mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
    }

    /**
     * To be called when there's a state change in StatusBarKeyguardViewManager.
     */
    public void onKeyguardViewManagerStatesUpdated() {
        logStateToEventlog();
    }

    @Override  // UnlockMethodCache.OnUnlockMethodChangedListener
    public void onUnlockMethodStateChanged() {
        logStateToEventlog();
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        if (inPinnedMode) {
            mStatusBarWindowController.setHeadsUpShowing(true);
            mStatusBarWindowController.setForceStatusBarVisible(true);
            if (mNotificationPanel.isFullyCollapsed()) {
                // We need to ensure that the touchable region is updated before the window will be
                // resized, in order to not catch any touches. A layout will ensure that
                // onComputeInternalInsets will be called and after that we can resize the layout. Let's
                // make sure that the window stays small for one frame until the touchableRegion is set.
                mNotificationPanel.requestLayout();
                mStatusBarWindowController.setForceWindowCollapsed(true);
                mNotificationPanel.post(() -> {
                    mStatusBarWindowController.setForceWindowCollapsed(false);
                });
            }
        } else {
            if (!mNotificationPanel.isFullyCollapsed() || mNotificationPanel.isTracking()) {
                // We are currently tracking or is open and the shade doesn't need to be kept
                // open artificially.
                mStatusBarWindowController.setHeadsUpShowing(false);
            } else {
                // we need to keep the panel open artificially, let's wait until the animation
                // is finished.
                mHeadsUpManager.setHeadsUpGoingAway(true);
                mNotificationPanel.runAfterAnimationFinished(() -> {
                    if (!mHeadsUpManager.hasPinnedHeadsUp()) {
                        mStatusBarWindowController.setHeadsUpShowing(false);
                        mHeadsUpManager.setHeadsUpGoingAway(false);
                    }
                    mRemoteInputManager.onPanelCollapsed();
                });
            }
        }
    }

    @Override
    public void onHeadsUpPinned(NotificationEntry entry) {
        dismissVolumeDialog();
    }

    @Override
    public void onHeadsUpUnPinned(NotificationEntry entry) {
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        mEntryManager.updateNotifications();
    }

    @Override
    public void onAmbientStateChanged(NotificationEntry entry, boolean isAmbient) {
        mEntryManager.updateNotifications();
        if (isAmbient) {
            mDozeServiceHost.fireNotificationPulse();
        } else if (!mAmbientPulseManager.hasNotifications()) {
            // There are no longer any notifications to show.  We should end the pulse now.
            mDozeScrimController.pulseOutNow();
        }
    }

    public boolean isKeyguardCurrentlySecure() {
        return !mUnlockMethodCache.canSkipBouncer();
    }

    public void setPanelExpanded(boolean isExpanded) {
        mPanelExpanded = isExpanded;
        updateHideIconsForBouncer(false /* animate */);
        mStatusBarWindowController.setPanelExpanded(isExpanded);
        mVisualStabilityManager.setPanelExpanded(isExpanded);
        if (isExpanded && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from setExpandedHeight");
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
        return mPulsing;
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        return mNotificationPanel.hideStatusBarIconsWhenExpanded();
    }

    @Override
    public void onColorsChanged(ColorExtractor extractor, int which) {
        updateTheme();
    }

    @Nullable
    public View getAmbientIndicationContainer() {
        return mAmbientIndicationContainer;
    }

    @Override
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

    public boolean isHeadsUpShouldBeVisible() {
        return mHeadsUpAppearanceController.shouldBeVisible();
    }

    //TODO: These can / should probably be moved to NotificationPresenter or ShadeController
    @Override
    public void onLaunchAnimationCancelled() {
        if (!mPresenter.isCollapsing()) {
            onClosingFinished();
        }
    }

    @Override
    public void onExpandAnimationFinished(boolean launchIsFullScreen) {
        if (!mPresenter.isCollapsing()) {
            onClosingFinished();
        }
        if (launchIsFullScreen) {
            instantCollapseNotificationPanel();
        }
    }

    @Override
    public void onExpandAnimationTimedOut() {
        if (mPresenter.isPresenterFullyCollapsed() && !mPresenter.isCollapsing()
                && mActivityLaunchAnimator != null
                && !mActivityLaunchAnimator.isLaunchForActivity()) {
            onClosingFinished();
        } else {
            collapsePanel(true /* animate */);
        }
    }

    @Override
    public boolean areLaunchAnimationsEnabled() {
        return mState == StatusBarState.SHADE;
    }

    public boolean isDeviceInVrMode() {
        return mPresenter.isDeviceInVrMode();
    }

    public NotificationPresenter getPresenter() {
        return mPresenter;
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
                    animateCollapsePanels();
                    break;
                case MSG_LAUNCH_TRANSITION_TIMEOUT:
                    onLaunchTransitionTimeout();
                    break;
            }
        }
    }

    public void maybeEscalateHeadsUp() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            final StatusBarNotification sbn = entry.notification;
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
        if (!mCommandQueue.panelsEnabled() || !mKeyguardMonitor.isDeviceInteractive()
                || mKeyguardMonitor.isShowing() && !mKeyguardMonitor.isOccluded()) {
            return;
        }

        // Panels are not available in setup
        if (!mUserSetup) return;

        if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP == key) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_UP);
            mNotificationPanel.collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        } else if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN == key) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_DOWN);
            if (mNotificationPanel.isFullyCollapsed()) {
                if (mVibrateOnOpening) {
                    mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
                }
                mNotificationPanel.expand(true /* animate */);
                mMetricsLogger.count(NotificationPanelView.COUNTER_PANEL_OPEN, 1);
            } else if (!mNotificationPanel.isInSettings() && !mNotificationPanel.isExpanding()){
                mNotificationPanel.flingSettings(0 /* velocity */,
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
        mStatusBarWindowController.setPanelVisible(true);

        visibilityChanged(true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, !force /* animate */);
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
    }

    public void animateCollapsePanels() {
        animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
    }

    private final Runnable mAnimateCollapsePanels = this::animateCollapsePanels;

    public void postAnimateCollapsePanels() {
        mHandler.post(mAnimateCollapsePanels);
    }

    public void postAnimateForceCollapsePanels() {
        mHandler.post(() -> {
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
        });
    }

    public void postAnimateOpenPanels() {
        mHandler.sendEmptyMessage(MSG_OPEN_SETTINGS_PANEL);
    }

    @Override
    public void togglePanel() {
        if (mPanelExpanded) {
            animateCollapsePanels();
        } else {
            animateExpandNotificationsPanel();
        }
    }

    public void animateCollapsePanels(int flags) {
        animateCollapsePanels(flags, false /* force */, false /* delayed */,
                1.0f /* speedUpFactor */);
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        animateCollapsePanels(flags, force, false /* delayed */, 1.0f /* speedUpFactor */);
    }

    public void animateCollapsePanels(int flags, boolean force, boolean delayed) {
        animateCollapsePanels(flags, force, delayed, 1.0f /* speedUpFactor */);
    }

    public void animateCollapsePanels(int flags, boolean force, boolean delayed,
            float speedUpFactor) {
        if (!force && mState != StatusBarState.SHADE) {
            runPostCollapseRunnables();
            return;
        }
        if (SPEW) {
            Log.d(TAG, "animateCollapse():"
                    + " mExpandedVisible=" + mExpandedVisible
                    + " flags=" + flags);
        }

        if ((flags & CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL) == 0) {
            if (!mHandler.hasMessages(MSG_HIDE_RECENT_APPS)) {
                mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
                mHandler.sendEmptyMessage(MSG_HIDE_RECENT_APPS);
            }
        }

        // TODO(b/62444020): remove when this bug is fixed
        Log.v(TAG, "mStatusBarWindow: " + mStatusBarWindow + " canPanelBeCollapsed(): "
                + mNotificationPanel.canPanelBeCollapsed());
        if (mStatusBarWindow != null && mNotificationPanel.canPanelBeCollapsed()) {
            // release focus immediately to kick off focus change transition
            mStatusBarWindowController.setStatusBarFocusable(false);

            mStatusBarWindow.cancelExpandHelper();
            mStatusBarView.collapsePanel(true /* animate */, delayed, speedUpFactor);
        } else {
            mBubbleController.collapseStack();
        }
    }

    private void runPostCollapseRunnables() {
        ArrayList<Runnable> clonedList = new ArrayList<>(mPostCollapseRunnables);
        mPostCollapseRunnables.clear();
        int size = clonedList.size();
        for (int i = 0; i < size; i++) {
            clonedList.get(i).run();
        }
        mStatusBarKeyguardViewManager.readyForKeyguardDone();
    }

    public void dispatchNotificationsPanelTouchEvent(MotionEvent ev) {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }
        mNotificationPanel.dispatchTouchEvent(ev);

        int action = ev.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            // Start ignoring all touch events coming to status bar window.
            // TODO: handle case where ACTION_UP is not sent over the binder
            mStatusBarWindowController.setNotTouchable(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mStatusBarWindowController.setNotTouchable(false);
        }
    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!mCommandQueue.panelsEnabled()) {
            return ;
        }

        mNotificationPanel.expandWithoutQs();

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
            mQSPanel.openDetails(subPanel);
        }
        mNotificationPanel.expandWithQs();

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

        if (!mExpandedVisible || mStatusBarWindow == null) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mStatusBarView.collapsePanel(/*animate=*/ false, false /* delayed*/,
                1.0f /* speedUpFactor */);

        mNotificationPanel.closeQs();

        mExpandedVisible = false;
        visibilityChanged(false);

        // Shrink the window to the size of the status bar only
        mStatusBarWindowController.setPanelVisible(false);
        mStatusBarWindowController.setForceStatusBarVisible(false);

        // Close any guts that might be visible
        mGutsManager.closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                true /* removeControls */, -1 /* x */, -1 /* y */, true /* resetMenu */);

        runPostCollapseRunnables();
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        if (!mNotificationActivityStarter.isCollapsingToShowActivityOverLockscreen()) {
            showBouncerIfKeyguard();
        } else if (DEBUG) {
            Log.d(TAG, "Not showing bouncer due to activity showing over lockscreen");
        }
        mCommandQueue.recomputeDisableFlags(
                mDisplayId, mNotificationPanel.hideStatusBarIconsWhenExpanded() /* animate */);

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
        if (mStatusBarWindow != null
                && window == StatusBarManager.WINDOW_STATUS_BAR
                && mStatusBarWindowState != state) {
            mStatusBarWindowState = state;
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Status bar " + windowStateToString(state));
            if (!showing && mState == StatusBarState.SHADE) {
                mStatusBarView.collapsePanel(false /* animate */, false /* delayed */,
                        1.0f /* speedUpFactor */);
            }
            if (mStatusBarView != null) {
                mStatusBarWindowHidden = state == WINDOW_STATE_HIDDEN;
                updateHideIconsForBouncer(false /* animate */);
            }
        }
    }

    @Override // CommandQueue
    public void setSystemUiVisibility(int displayId, int vis, int fullscreenStackVis,
            int dockedStackVis, int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
        if (displayId != mDisplayId) {
            return;
        }
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal&~mask) | (vis&mask);
        final int diff = newVal ^ oldVal;
        if (DEBUG) Log.d(TAG, String.format(
                "setSystemUiVisibility displayId=%d vis=%s mask=%s oldVal=%s newVal=%s diff=%s",
                displayId, Integer.toHexString(vis), Integer.toHexString(mask),
                Integer.toHexString(oldVal), Integer.toHexString(newVal),
                Integer.toHexString(diff)));
        boolean sbModeChanged = false;
        if (diff != 0) {
            mSystemUiVisibility = newVal;

            // update low profile
            if ((diff & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
                updateAreThereNotifications();
            }

            // ready to unhide
            if ((vis & View.STATUS_BAR_UNHIDE) != 0) {
                mNoAnimationOnNextBarModeChange = true;
            }

            // update status bar mode
            final int sbMode = computeStatusBarMode(oldVal, newVal);

            sbModeChanged = sbMode != -1;
            if (sbModeChanged && sbMode != mStatusBarMode) {
                mStatusBarMode = sbMode;
                checkBarModes();
                mAutoHideController.touchAutoHide();
            }
        }
        mLightBarController.onSystemUiVisibilityChanged(fullscreenStackVis, dockedStackVis,
                mask, fullscreenStackBounds, dockedStackBounds, sbModeChanged, mStatusBarMode);
    }

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
        if (mDozing || mKeyguardManager.isKeyguardLocked()) {
            // on ambient or lockscreen, hide notification panel
            WirelessChargingAnimation.makeWirelessChargingAnimation(mContext, null,
                    batteryLevel, new WirelessChargingAnimation.Callback() {
                        @Override
                        public void onAnimationStarting() {
                            CrossFadeHelper.fadeOut(mNotificationPanel, 1);
                        }

                        @Override
                        public void onAnimationEnded() {
                            CrossFadeHelper.fadeIn(mNotificationPanel);
                        }
                    }, mDozing).show();
        } else {
            // workspace
            WirelessChargingAnimation.makeWirelessChargingAnimation(mContext, null,
                    batteryLevel, null, false).show();
        }
    }

    protected @TransitionMode int computeStatusBarMode(int oldVal, int newVal) {
        return computeBarMode(oldVal, newVal);
    }

    protected BarTransitions getStatusBarTransitions() {
        return mStatusBarView.getBarTransitions();
    }

    protected @TransitionMode int computeBarMode(int oldVis, int newVis) {
        final int oldMode = barMode(oldVis);
        final int newMode = barMode(newVis);
        if (oldMode == newMode) {
            return -1; // no mode change
        }
        return newMode;
    }

    private @TransitionMode int barMode(int vis) {
        int lightsOutTransparent = View.SYSTEM_UI_FLAG_LOW_PROFILE | View.STATUS_BAR_TRANSPARENT;
        if ((vis & View.STATUS_BAR_TRANSIENT) != 0) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((vis & View.STATUS_BAR_TRANSLUCENT) != 0) {
            return MODE_TRANSLUCENT;
        } else if ((vis & lightsOutTransparent) == lightsOutTransparent) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((vis & View.STATUS_BAR_TRANSPARENT) != 0) {
            return MODE_TRANSPARENT;
        } else if ((vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
            return MODE_LIGHTS_OUT;
        } else {
            return MODE_OPAQUE;
        }
    }

    void checkBarModes() {
        if (mDemoMode) return;
        if (mStatusBarView != null) checkBarMode(mStatusBarMode, mStatusBarWindowState,
                getStatusBarTransitions());
        mNavigationBarController.checkNavBarModes(mDisplayId);
        mNoAnimationOnNextBarModeChange = false;
    }

    // Called by NavigationBarFragment
    void setQsScrimEnabled(boolean scrimEnabled) {
        mNotificationPanel.setQsScrimEnabled(scrimEnabled);
    }

    void checkBarMode(@TransitionMode int mode, @WindowVisibleState int windowState,
            BarTransitions transitions) {
        final boolean anim = !mNoAnimationOnNextBarModeChange && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN;
        transitions.transitionTo(mode, anim);
    }

    private void finishBarAnimations() {
        if (mStatusBarView != null) {
            mStatusBarView.getBarTransitions().finishAnimations();
        }
        mNavigationBarController.finishBarAnimations(mDisplayId);
    }

    private final Runnable mCheckBarModes = this::checkBarModes;

    public void setInteracting(int barWindow, boolean interacting) {
        final boolean changing = ((mInteractingWindows & barWindow) != 0) != interacting;
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
        // manually dismiss the volume panel when interacting with the nav bar
        if (changing && interacting && barWindow == StatusBarManager.WINDOW_NAVIGATION_BAR) {
            mNavigationBarController.touchAutoDim(mDisplayId);
            dismissVolumeDialog();
        }
        checkBarModes();
    }

    private void dismissVolumeDialog() {
        if (mVolumeComponent != null) {
            mVolumeComponent.dismissNow();
        }
    }

    private boolean areLightsOn() {
        return 0 == (mSystemUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    public void setLightsOn(boolean on) {
        Log.v(TAG, "setLightsOn(" + on + ")");
        if (on) {
            setSystemUiVisibility(mDisplayId, 0, 0, 0, View.SYSTEM_UI_FLAG_LOW_PROFILE,
                    mLastFullscreenStackBounds, mLastDockedStackBounds);
        } else {
            setSystemUiVisibility(mDisplayId, View.SYSTEM_UI_FLAG_LOW_PROFILE, 0, 0,
                    View.SYSTEM_UI_FLAG_LOW_PROFILE, mLastFullscreenStackBounds,
                    mLastDockedStackBounds);
        }
    }

    @Override
    public void topAppWindowChanged(int displayId, boolean showMenu) {
        if (mDisplayId != displayId) return;
        if (SPEW) {
            Log.d(TAG, "display#" + displayId + ": "
                    + (showMenu ? "showing" : "hiding") + " the MENU button");
        }

        // See above re: lights-out policy for legacy apps.
        if (showMenu) setLightsOn(true);
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
        pw.print("  mZenMode=");
        pw.println(Settings.Global.zenModeToString(Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.ZEN_MODE,
                Settings.Global.ZEN_MODE_OFF)));

        if (mStatusBarView != null) {
            dumpBarTransitions(pw, "mStatusBarView", mStatusBarView.getBarTransitions());
        }
        pw.println("  StatusBarWindowView: ");
        if (mStatusBarWindow != null) {
            mStatusBarWindow.dump(fd, pw, args);
        }

        pw.println("  mMediaManager: ");
        if (mMediaManager != null) {
            mMediaManager.dump(fd, pw, args);
        }

        pw.println("  Panels: ");
        if (mNotificationPanel != null) {
            pw.println("    mNotificationPanel=" +
                mNotificationPanel + " params=" + mNotificationPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanel.dump(fd, pw, args);
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
        final boolean lightWpTheme = mContext.getThemeResId() == R.style.Theme_SystemUI_Light;
        pw.println("    light wallpaper theme: " + lightWpTheme);

        DozeLog.dump(pw);

        if (mBiometricUnlockController != null) {
            mBiometricUnlockController.dump(pw);
        }

        if (mKeyguardIndicationController != null) {
            mKeyguardIndicationController.dump(fd, pw, args);
        }

        if (mScrimController != null) {
            mScrimController.dump(fd, pw, args);
        }

        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.dump(pw);
        }

        if (DUMPTRUCK) {
            synchronized (mEntryManager.getNotificationData()) {
                mEntryManager.getNotificationData().dump(pw, "  ");
            }

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
        if (mGroupManager != null) {
            mGroupManager.dump(fd, pw, args);
        } else {
            pw.println("  mGroupManager: null");
        }

        if (mLightBarController != null) {
            mLightBarController.dump(fd, pw, args);
        }

        if (mKeyguardUpdateMonitor != null) {
            mKeyguardUpdateMonitor.dump(fd, pw, args);
        }

        FalsingManager.getInstance(mContext).dump(pw);
        FalsingLog.dump(pw);

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }
    }

    static void dumpBarTransitions(PrintWriter pw, String var, BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        pw.println(BarTransitions.modeToString(transitions.getMode()));
    }

    public void createAndAddWindows() {
        addStatusBarWindow();
    }

    private void addStatusBarWindow() {
        makeStatusBarView();
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
        mStatusBarWindowController.add(mStatusBarWindow, getStatusBarHeight());
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

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade, int flags) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade,
                false /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */,
                flags);
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, 0);
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            final boolean dismissShade, final boolean disallowEnterPictureInPictureWhileLaunching,
            final Callback callback, int flags) {
        if (onlyProvisioned && !mDeviceProvisionedController.isDeviceProvisioned()) return;

        final boolean afterKeyguardGone = PreviewInflater.wouldLaunchResolverActivity(
                mContext, intent, mLockscreenUserManager.getCurrentUserId());
        Runnable runnable = () -> {
            mAssistManager.hideAssist();
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(flags);
            int result = ActivityManager.START_CANCELED;
            ActivityOptions options = new ActivityOptions(getActivityOptions(
                    null /* remoteAnimation */));
            options.setDisallowEnterPictureInPictureWhileLaunching(
                    disallowEnterPictureInPictureWhileLaunching);
            if (intent == KeyguardBottomAreaView.INSECURE_CAMERA_INTENT) {
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
            try {
                result = ActivityTaskManager.getService().startActivityAsUser(
                        null, mContext.getBasePackageName(),
                        intent,
                        intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null,
                        options.toBundle(), UserHandle.CURRENT.getIdentifier());
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to start activity", e);
            }
            if (callback != null) {
                callback.onActivityStarted(result);
            }
        };
        Runnable cancelRunnable = () -> {
            if (callback != null) {
                callback.onActivityStarted(ActivityManager.START_CANCELED);
            }
        };
        executeRunnableDismissingKeyguard(runnable, cancelRunnable, dismissShade,
                afterKeyguardGone, true /* deferred */);
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
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */,
                            true /* delayed*/);
                } else {

                    // Do it after DismissAction has been processed to conserve the needed ordering.
                    mHandler.post(this::runPostCollapseRunnables);
                }
            } else if (isInLaunchTransition() && mNotificationPanel.isLaunchTransitionFinished()) {

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
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                KeyboardShortcuts.dismiss();
                if (mRemoteInputManager.getController() != null) {
                    mRemoteInputManager.getController().closeRemoteInputs();
                }
                if (mBubbleController.isStackExpanded()) {
                    mBubbleController.collapseStack();
                }
                if (mLockscreenUserManager.isCurrentProfile(getSendingUserId())) {
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                    animateCollapsePanels(flags);
                }
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mStatusBarWindowController != null) {
                    mStatusBarWindowController.setNotTouchable(false);
                }
                if (mBubbleController.isStackExpanded()) {
                    mBubbleController.collapseStack();
                }
                finishBarAnimations();
                resetUserExpandedStates();
            }
            else if (DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG.equals(action)) {
                mQSPanel.showDeviceMonitoringDialog();
            }
        }
    };

    private final BroadcastReceiver mDemoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (ACTION_DEMO.equals(action)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    String command = bundle.getString("command", "").trim().toLowerCase();
                    if (command.length() > 0) {
                        try {
                            dispatchDemoCommand(command, bundle);
                        } catch (Throwable t) {
                            Log.w(TAG, "Error running demo command, intent=" + intent, t);
                        }
                    }
                }
            } else if (ACTION_FAKE_ARTWORK.equals(action)) {
                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    mPresenter.updateMediaMetaData(true, true);
                }
            }
        }
    };

    public void resetUserExpandedStates() {
        ArrayList<NotificationEntry> activeNotifications = mEntryManager.getNotificationData()
                .getActiveNotifications();
        final int notificationCount = activeNotifications.size();
        for (int i = 0; i < notificationCount; i++) {
            NotificationEntry entry = activeNotifications.get(i);
            entry.resetUserExpansion();
        }
    }

    private void executeWhenUnlocked(OnDismissAction action) {
        if (mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        }
        dismissKeyguardThenExecute(action, null /* cancelAction */, false /* afterKeyguardGone */);
    }

    protected void dismissKeyguardThenExecute(OnDismissAction action, boolean afterKeyguardGone) {
        dismissKeyguardThenExecute(action, null /* cancelRunnable */, afterKeyguardGone);
    }

    @Override
    public void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancelAction,
            boolean afterKeyguardGone) {
        if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP
                && mUnlockMethodCache.canSkipBouncer()
                && !mStatusBarStateController.leaveOpenOnKeyguardHide()
                && isPulsing()) {
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

    // SystemUIService notifies SystemBars of configuration changes, which then calls down here
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

    @Override
    public void setLockscreenUser(int newUserId) {
        mLockscreenWallpaper.setCurrentUser(newUserId);
        mScrimController.setCurrentUser(newUserId);
        mWallpaperChangedReceiver.onReceive(mContext, null);
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
        if (mQSPanel != null) {
            mQSPanel.updateResources();
        }

        loadDimens();

        if (mStatusBarView != null) {
            mStatusBarView.updateResources();
        }
        if (mNotificationPanel != null) {
            mNotificationPanel.updateResources();
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.updateResources();
        }
    }

    protected void loadDimens() {
        final Resources res = mContext.getResources();

        int oldBarHeight = mNaturalBarHeight;
        mNaturalBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        if (mStatusBarWindowController != null && mNaturalBarHeight != oldBarHeight) {
            mStatusBarWindowController.setBarHeight(mNaturalBarHeight);
        }

        if (DEBUG) Log.v(TAG, "defineSlots");
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

    void handlePeekToExpandTransistion() {
        try {
            // consider the transition from peek to expanded to be a panel open,
            // but not one that clears notification effects.
            int notificationLoad = mEntryManager.getNotificationData()
                    .getActiveNotifications().size();
            mBarService.onPanelRevealed(false, notificationLoad);
        } catch (RemoteException ex) {
            // Won't fail unless the world has ended.
        }
    }

    /**
     * The LEDs are turned off when the notification panel is shown, even just a little bit.
     * See also StatusBar.setPanelExpanded for another place where we attempt to do this.
     */
    private void handleVisibleToUserChangedImpl(boolean visibleToUser) {
        if (visibleToUser) {
            boolean pinnedHeadsUp = mHeadsUpManager.hasPinnedHeadsUp();
            boolean clearNotificationEffects =
                    !mPresenter.isPresenterFullyCollapsed() &&
                            (mState == StatusBarState.SHADE
                                    || mState == StatusBarState.SHADE_LOCKED);
            int notificationLoad = mEntryManager.getNotificationData().getActiveNotifications()
                    .size();
            if (pinnedHeadsUp && mPresenter.isPresenterFullyCollapsed()) {
                notificationLoad = 1;
            }
            final int finalNotificationLoad = notificationLoad;
            mUiOffloadThread.submit(() -> {
                try {
                    mBarService.onPanelRevealed(clearNotificationEffects,
                            finalNotificationLoad);
                } catch (RemoteException ex) {
                    // Won't fail unless the world has ended.
                }
            });
        } else {
            mUiOffloadThread.submit(() -> {
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
        boolean isSecure = mUnlockMethodCache.isMethodSecure();
        boolean canSkipBouncer = mUnlockMethodCache.canSkipBouncer();
        int stateFingerprint = getLoggingFingerprint(mState,
                isShowing,
                isOccluded,
                isBouncerShowing,
                isSecure,
                canSkipBouncer);
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
                    canSkipBouncer ? 1 : 0);
            mLastLoggedStateFingerprint = stateFingerprint;
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
    public void postStartActivityDismissingKeyguard(final PendingIntent intent) {
        mHandler.post(() -> startPendingIntentDismissingKeyguard(intent));
    }

    @Override
    public void postStartActivityDismissingKeyguard(final Intent intent, int delay) {
        mHandler.postDelayed(() ->
                handleStartActivityDismissingKeyguard(intent, true /*onlyProvisioned*/), delay);
    }

    private void handleStartActivityDismissingKeyguard(Intent intent, boolean onlyProvisioned) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, true /* dismissShade */);
    }

    private boolean mDemoModeAllowed;
    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoModeAllowed) {
            mDemoModeAllowed = Settings.Global.getInt(mContext.getContentResolver(),
                    DEMO_MODE_ALLOWED, 0) != 0;
        }
        if (!mDemoModeAllowed) return;
        if (command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            checkBarModes();
        } else if (!mDemoMode) {
            // automatically enter demo mode on first demo command
            dispatchDemoCommand(COMMAND_ENTER, new Bundle());
        }
        boolean modeChange = command.equals(COMMAND_ENTER) || command.equals(COMMAND_EXIT);
        if ((modeChange || command.equals(COMMAND_VOLUME)) && mVolumeComponent != null) {
            mVolumeComponent.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_CLOCK)) {
            dispatchDemoCommandToView(command, args, R.id.clock);
        }
        if (modeChange || command.equals(COMMAND_BATTERY)) {
            mBatteryController.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_STATUS)) {
            ((StatusBarIconControllerImpl) mIconController).dispatchDemoCommand(command, args);
        }
        if (mNetworkController != null && (modeChange || command.equals(COMMAND_NETWORK))) {
            mNetworkController.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_NOTIFICATIONS)) {
            View notifications = mStatusBarView == null ? null
                    : mStatusBarView.findViewById(R.id.notification_icon_area);
            if (notifications != null) {
                String visible = args.getString("visible");
                int vis = mDemoMode && "false".equals(visible) ? View.INVISIBLE : View.VISIBLE;
                notifications.setVisibility(vis);
            }
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
                if (mStatusBarView != null) {
                    mStatusBarView.getBarTransitions().transitionTo(barMode, animate);
                }
                mNavigationBarController.transitionTo(mDisplayId, barMode, animate);
            }
        }
        if (modeChange || command.equals(COMMAND_OPERATOR)) {
            dispatchDemoCommandToView(command, args, R.id.operator_name);
        }
    }

    private void dispatchDemoCommandToView(String command, Bundle args, int id) {
        if (mStatusBarView == null) return;
        View v = mStatusBarView.findViewById(id);
        if (v instanceof DemoMode) {
            ((DemoMode)v).dispatchDemoCommand(command, args);
        }
    }

    public void showKeyguard() {
        mStatusBarStateController.setKeyguardRequested(true);
        mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
        mPendingRemoteInputView = null;
        updateIsKeyguard();
        mAssistManager.onLockscreenShown();
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

    private boolean updateIsKeyguard() {
        boolean wakeAndUnlocking = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardForDozing = mDozingRequested &&
                (!mDeviceInteractive || isGoingToSleep() && (isScreenFullyOff() || mIsKeyguard));
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
        if (mKeyguardMonitor.isLaunchTransitionFadingAway()) {
            mNotificationPanel.animate().cancel();
            onLaunchTransitionFadingEnded();
        }
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (mUserSwitcherController != null && mUserSwitcherController.useFullscreenUserSwitcher()) {
            mStatusBarStateController.setState(StatusBarState.FULLSCREEN_USER_SWITCHER);
        } else if (!mPulseExpansionHandler.isWakingToShadeLocked()){
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
        updatePanelExpansionForKeyguard();
        if (mDraggedDownEntry != null) {
            mDraggedDownEntry.setUserLocked(false);
            mDraggedDownEntry.notifyHeightChanged(false /* needsAnimation */);
            mDraggedDownEntry = null;
        }
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mBiometricUnlockController.getMode()
                != BiometricUnlockController.MODE_WAKE_AND_UNLOCK) {
            instantExpandNotificationsPanel();
        } else if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            instantCollapseNotificationPanel();
        }
    }

    private void onLaunchTransitionFadingEnded() {
        mNotificationPanel.setAlpha(1.0f);
        mNotificationPanel.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        runLaunchTransitionEndRunnable();
        mKeyguardMonitor.setLaunchTransitionFadingAway(false);
        mPresenter.updateMediaMetaData(true /* metaDataChanged */, true);
    }

    public void addPostCollapseAction(Runnable r) {
        mPostCollapseRunnables.add(r);
    }

    public boolean isInLaunchTransition() {
        return mNotificationPanel.isLaunchTransitionRunning()
                || mNotificationPanel.isLaunchTransitionFinished();
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
            mKeyguardMonitor.setLaunchTransitionFadingAway(true);
            if (beforeFading != null) {
                beforeFading.run();
            }
            updateScrimController();
            mPresenter.updateMediaMetaData(false, true);
            mNotificationPanel.setAlpha(1);
            mNotificationPanel.animate()
                    .alpha(0)
                    .setStartDelay(FADE_KEYGUARD_START_DELAY)
                    .setDuration(FADE_KEYGUARD_DURATION)
                    .withLayer()
                    .withEndAction(this::onLaunchTransitionFadingEnded);
            mCommandQueue.appTransitionStarting(mDisplayId, SystemClock.uptimeMillis(),
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        };
        if (mNotificationPanel.isLaunchTransitionRunning()) {
            mNotificationPanel.setLaunchTransitionEndRunnable(hideRunnable);
        } else {
            hideRunnable.run();
        }
    }

    /**
     * Fades the content of the Keyguard while we are dozing and makes it invisible when finished
     * fading.
     */
    public void fadeKeyguardWhilePulsing() {
        mNotificationPanel.animate()
                .alpha(0f)
                .setStartDelay(0)
                .setDuration(FADE_KEYGUARD_DURATION_PULSING)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(()-> {
                    hideKeyguard();
                    mStatusBarKeyguardViewManager.onKeyguardFadedAway();
                }).start();
    }

    /**
     * Plays the animation when an activity that was occluding Keyguard goes away.
     */
    public void animateKeyguardUnoccluding() {
        mNotificationPanel.setExpandedFraction(0f);
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
        mNotificationPanel.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        mNotificationPanel.resetViews(false /* animate */);
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
            mEntryManager.updateNotifications();
        }
        if (mStatusBarStateController.leaveOpenOnKeyguardHide()) {
            if (!mStatusBarStateController.isKeyguardRequested()) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            }
            long delay = mKeyguardMonitor.calculateGoingToFullShadeDelay();
            mNotificationPanel.animateToFullShade(delay);
            if (mDraggedDownEntry != null) {
                mDraggedDownEntry.setUserLocked(false);
                mDraggedDownEntry = null;
            }

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            mNavigationBarController.disableAnimationsDuringHide(mDisplayId, delay);
        } else if (!mNotificationPanel.isCollapsing()) {
            instantCollapseNotificationPanel();
        }

        // Keyguard state has changed, but QS is not listening anymore. Make sure to update the tile
        // visibilities so next time we open the panel we know the correct height already.
        if (mQSPanel != null) {
            mQSPanel.refreshAllTiles();
        }
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        releaseGestureWakeLock();
        mNotificationPanel.onAffordanceLaunchEnded();
        mNotificationPanel.animate().cancel();
        mNotificationPanel.setAlpha(1f);
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
        mKeyguardMonitor.notifyKeyguardGoingAway(true);
        mCommandQueue.appTransitionPending(mDisplayId, true /* forced */);
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     *
     * @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration) {
        mCommandQueue.appTransitionStarting(mDisplayId, startTime + fadeoutDuration
                        - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, fadeoutDuration > 0 /* animate */);
        mCommandQueue.appTransitionStarting(mDisplayId,
                    startTime - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mKeyguardMonitor.notifyKeyguardFadingAway(delay, fadeoutDuration);
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    public void finishKeyguardFadingAway() {
        mKeyguardMonitor.notifyKeyguardDoneFading();
        mScrimController.setExpansionAffectsAlpha(true);
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {

        // Lock wallpaper defines the color of the majority of the views, hence we'll use it
        // to set our default theme.
        final boolean lockDarkText = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK, true
                /* ignoreVisibility */).supportsDarkText();
        final int themeResId = lockDarkText ? R.style.Theme_SystemUI_Light : R.style.Theme_SystemUI;
        if (mContext.getThemeResId() != themeResId) {
            mContext.setTheme(themeResId);
            Dependency.get(ConfigurationController.class).notifyThemeChanged();
        }
    }

    private void updateDozingState() {
        Trace.traceCounter(Trace.TRACE_TAG_APP, "dozing", mDozing ? 1 : 0);
        Trace.beginSection("StatusBar#updateDozingState");

        boolean sleepingFromKeyguard =
                mStatusBarKeyguardViewManager.isGoingToSleepVisibleNotOccluded();
        boolean wakeAndUnlock = mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
        boolean animate = (!mDozing && mDozeServiceHost.shouldAnimateWakeup() && !wakeAndUnlock)
                || (mDozing && mDozeServiceHost.shouldAnimateScreenOff() && sleepingFromKeyguard);

        mNotificationPanel.setDozing(mDozing, animate, mWakeUpTouchLocation);
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

    protected boolean shouldUnlockOnMenuPressed() {
        return mDeviceInteractive && mState != StatusBarState.SHADE
            && mStatusBarKeyguardViewManager.shouldDismissOnMenuPressed();
    }

    public boolean onMenuPressed() {
        if (shouldUnlockOnMenuPressed()) {
            animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    public void endAffordanceLaunch() {
        releaseGestureWakeLock();
        mNotificationPanel.onAffordanceLaunchEnded();
    }

    public boolean onBackPressed() {
        boolean isScrimmedBouncer = mScrimController.getState() == ScrimState.BOUNCER_SCRIMMED;
        if (mStatusBarKeyguardViewManager.onBackPressed(isScrimmedBouncer /* hideImmediately */)) {
            if (!isScrimmedBouncer) {
                mNotificationPanel.expandWithoutQs();
            }
            return true;
        }
        if (mNotificationPanel.isQsExpanded()) {
            if (mNotificationPanel.isQsDetailShowing()) {
                mNotificationPanel.closeQsDetail();
            } else {
                mNotificationPanel.animateCloseQs(false /* animateAway */);
            }
            return true;
        }
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
            if (mNotificationPanel.canPanelBeCollapsed()) {
                animateCollapsePanels();
            } else {
                mBubbleController.performBackPressIfNeeded();
            }
            return true;
        }
        if (mKeyguardUserSwitcher != null && mKeyguardUserSwitcher.hideIfNotSimple(true)) {
            return true;
        }
        return false;
    }

    public boolean onSpacePressed() {
        if (mDeviceInteractive && mState != StatusBarState.SHADE) {
            animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL /* flags */, true /* force */);
            return true;
        }
        return false;
    }

    private void showBouncerIfKeyguard() {
        if ((mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED)
                && !mKeyguardViewMediator.isHiding()) {
            showBouncer(true /* scrimmed */);
        }
    }

    @Override
    public void showBouncer(boolean scrimmed) {
        mStatusBarKeyguardViewManager.showBouncer(scrimmed);
    }

    @Override
    public void instantExpandNotificationsPanel() {
        // Make our window larger and the panel expanded.
        makeExpandedVisible(true);
        mNotificationPanel.expand(false /* animate */);
        mCommandQueue.recomputeDisableFlags(mDisplayId, false /* animate */);
    }

    @Override
    public boolean closeShadeIfOpen() {
        if (!mNotificationPanel.isFullyCollapsed()) {
            mCommandQueue.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */);
            visibilityChanged(false);
            mAssistManager.hideAssist();
        }
        return false;
    }

    @Override
    public void postOnShadeExpanded(Runnable executable) {
        mNotificationPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (getStatusBarWindow().getHeight() != getStatusBarHeight()) {
                            mNotificationPanel.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);
                            mNotificationPanel.post(executable);
                        }
                    }
                });
    }

    private void instantCollapseNotificationPanel() {
        mNotificationPanel.instantCollapse();
        runPostCollapseRunnables();
    }

    @Override
    public void onStatePreChange(int oldState, int newState) {
        // If we're visible and switched to SHADE_LOCKED (the user dragged
        // down on the lockscreen), clear notification LED, vibration,
        // ringing.
        // Other transitions are covered in handleVisibleToUserChanged().
        if (mVisible && (newState == StatusBarState.SHADE_LOCKED
                || (((SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class))
                .goingToFullShade()))) {
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
        updateDozing();
        updateTheme();
        mNavigationBarController.touchAutoDim(mDisplayId);
        Trace.beginSection("StatusBar#updateKeyguardState");
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardIndicationController.setVisible(true);
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(true,
                        mStatusBarStateController.fromShadeLocked());
            }
            if (mStatusBarView != null) mStatusBarView.removePendingHideExpandedRunnables();
            if (mAmbientIndicationContainer != null) {
                mAmbientIndicationContainer.setVisibility(View.VISIBLE);
            }
        } else {
            mKeyguardIndicationController.setVisible(false);
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(false,
                        mStatusBarStateController.goingToFullShade() ||
                                mState == StatusBarState.SHADE_LOCKED ||
                                mStatusBarStateController.fromShadeLocked());
            }
            if (mAmbientIndicationContainer != null) {
                mAmbientIndicationContainer.setVisibility(View.INVISIBLE);
            }
        }
        updateDozingState();
        checkBarModes();
        updateScrimController();
        mPresenter.updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
        mKeyguardMonitor.notifyKeyguardState(mStatusBarKeyguardViewManager.isShowing(),
                mUnlockMethodCache.isMethodSecure(),
                mStatusBarKeyguardViewManager.isOccluded());
        Trace.endSection();
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        Trace.beginSection("StatusBar#updateDozing");
        mDozing = isDozing;

        // Collapse the notification panel if open
        boolean dozingAnimated = mDozingRequested
                && DozeParameters.getInstance(mContext).shouldControlScreenOff();
        mNotificationPanel.resetViews(dozingAnimated);

        updateQsExpansionEnabled();
        mKeyguardViewMediator.setAodShowing(mDozing);

        mEntryManager.updateNotifications();
        updateDozingState();
        updateScrimController();
        updateReportRejectedTouchVisibility();
        Trace.endSection();
    }

    private void updateDozing() {
        // When in wake-and-unlock while pulsing, keep dozing state until fully unlocked.
        boolean dozing = mDozingRequested && mState == StatusBarState.KEYGUARD
                || mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;
        // When in wake-and-unlock we may not have received a change to mState
        // but we still should not be dozing, manually set to false.
        if (mBiometricUnlockController.getMode() ==
                BiometricUnlockController.MODE_WAKE_AND_UNLOCK) {
            dozing = false;
        }

        mStatusBarStateController.setIsDozing(dozing);
    }

    public void onActivationReset() {
        mKeyguardIndicationController.hideTransientIndication();
    }

    public void onTrackingStarted() {
        runPostCollapseRunnables();
    }

    public void onClosingFinished() {
        runPostCollapseRunnables();
        if (!mPresenter.isPresenterFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            mStatusBarWindowController.setStatusBarFocusable(true);
        }
    }

    public void onUnlockHintStarted() {
        mFalsingManager.onUnlockHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.keyguard_unlock);
    }

    public void onHintFinished() {
        // Delay the reset a bit so the user can read the text.
        mKeyguardIndicationController.hideTransientIndicationDelayed(HINT_RESET_DELAY_MS);
    }

    public void onCameraHintStarted() {
        mFalsingManager.onCameraHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.camera_hint);
    }

    public void onVoiceAssistHintStarted() {
        mFalsingManager.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.voice_hint);
    }

    public void onPhoneHintStarted() {
        mFalsingManager.onLeftAffordanceHintStarted();
        mKeyguardIndicationController.showTransientIndication(R.string.phone_hint);
    }

    public void onTrackingStopped(boolean expand) {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            if (!expand && !mUnlockMethodCache.canSkipBouncer()) {
                showBouncer(false /* scrimmed */);
            }
        }
    }

    // TODO: Figure out way to remove these.
    public NavigationBarView getNavigationBarView() {
        return mNavigationBarController.getDefaultNavigationBarView();
    }

    /**
     * TODO: Remove this method. Views should not be passed forward. Will cause theme issues.
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mNotificationPanel.getKeyguardBottomAreaView();
    }

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * <p>If secure without redaction or no security: Go to {@link StatusBarState#SHADE_LOCKED}.</p>
     *
     * @param expandView The view to expand after going to the shade.
     */
    public void goToLockedShade(View expandView) {
        if ((mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
            return;
        }

        int userId = mLockscreenUserManager.getCurrentUserId();
        ExpandableNotificationRow row = null;
        NotificationEntry entry = null;
        if (expandView instanceof ExpandableNotificationRow) {
            entry = ((ExpandableNotificationRow) expandView).getEntry();
            entry.setUserExpanded(true /* userExpanded */, true /* allowChildExpansion */);
            // Indicate that the group expansion is changing at this time -- this way the group
            // and children backgrounds / divider animations will look correct.
            entry.setGroupExpansionChanging(true);
            if (entry.notification != null) {
                userId = entry.notification.getUserId();
            }
        }
        boolean fullShadeNeedsBouncer = !mLockscreenUserManager.
                userAllowsPrivateNotificationsInPublic(mLockscreenUserManager.getCurrentUserId())
                || !mLockscreenUserManager.shouldShowLockscreenNotifications()
                || mFalsingManager.shouldEnforceBouncer();
        if (mLockscreenUserManager.isLockscreenPublicMode(userId) && fullShadeNeedsBouncer) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
            showBouncerIfKeyguard();
            mDraggedDownEntry = entry;
            mPendingRemoteInputView = null;
        } else {
            mNotificationPanel.animateToFullShade(0 /* delay */);
            mStatusBarStateController.setState(StatusBarState.SHADE_LOCKED);
        }
    }

    /**
     * Goes back to the keyguard after hanging around in {@link StatusBarState#SHADE_LOCKED}.
     */
    public void goToKeyguard() {
        if (mState == StatusBarState.SHADE_LOCKED) {
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
    }

    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        if (mStatusBarView != null) mStatusBarView.setBouncerShowing(bouncerShowing);
        updateHideIconsForBouncer(true /* animate */);
        mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
        updateScrimController();
    }

    /**
     * Collapses the notification shade if it is tracking or expanded.
     */
    public void collapseShade() {
        if (mNotificationPanel.isTracking()) {
            mStatusBarWindow.cancelCurrentTouch();
        }
        if (mPanelExpanded && mState == StatusBarState.SHADE) {
            animateCollapsePanels();
        }
    }

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mNotificationPanel.onAffordanceLaunchEnded();
            releaseGestureWakeLock();
            mLaunchCameraOnScreenTurningOn = false;
            mDeviceInteractive = false;
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
            mVisualStabilityManager.setScreenOn(false);
            updateVisibleToUser();

            updateNotificationPanelTouchState();
            mStatusBarWindow.cancelCurrentTouch();
            if (mLaunchCameraOnFinishedGoingToSleep) {
                mLaunchCameraOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the state
                // is correct.
                mHandler.post(() -> onCameraLaunchGestureDetected(mLastCameraLaunchSource));
            }
            updateIsKeyguard();
        }

        @Override
        public void onStartedGoingToSleep() {
            notifyHeadsUpGoingToSleep();
            dismissVolumeDialog();
        }

        @Override
        public void onStartedWakingUp() {
            mDeviceInteractive = true;
            mWakeUpCoordinator.setWakingUp(true);
            mAmbientPulseManager.releaseAllImmediately();
            mVisualStabilityManager.setScreenOn(true);
            updateNotificationPanelTouchState();
            updateVisibleToUser();
            updateIsKeyguard();
            mDozeServiceHost.stopDozing();
            mPulseExpansionHandler.onStartedWakingUp();
        }

        @Override
        public void onFinishedWakingUp() {
            mWakeUpCoordinator.setWakingUp(false);
        }
    };

    /**
     * We need to disable touch events because these might
     * collapse the panel after we expanded it, and thus we would end up with a blank
     * Keyguard.
     */
    private void updateNotificationPanelTouchState() {
        mNotificationPanel.setTouchAndAnimationDisabled(!mDeviceInteractive && !mPulsing);
    }

    final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurningOn() {
            mFalsingManager.onScreenTurningOn();
            mNotificationPanel.onScreenTurningOn();

            if (mLaunchCameraOnScreenTurningOn) {
                mNotificationPanel.launchCamera(false, mLastCameraLaunchSource);
                mLaunchCameraOnScreenTurningOn = false;
            }

            updateScrimController();
        }

        @Override
        public void onScreenTurnedOn() {
            mScrimController.onScreenTurnedOn();
        }

        @Override
        public void onScreenTurnedOff() {
            mFalsingManager.onScreenOff();
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
        if (mKeyguardMonitor.isShowing()) {
            // Don't allow apps to trigger this from keyguard.
            return;
        }
        // Show screen pinning request, since this comes from an app, show 'no thanks', button.
        showScreenPinningRequest(taskId, true);
    }

    public void showScreenPinningRequest(int taskId, boolean allowCancel) {
        mScreenPinningRequest.showPrompt(taskId, allowCancel);
    }

    public boolean hasActiveNotifications() {
        return !mEntryManager.getNotificationData().getActiveNotifications().isEmpty();
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        if (displayId == mDisplayId) {
            getComponent(Divider.class).onAppTransitionFinished();
        }
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (displayId == mDisplayId) {
            getComponent(Divider.class).onAppTransitionFinished();
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
        if (!mNotificationPanel.canCameraGestureBeLaunched(
                mStatusBarKeyguardViewManager.isShowing() && mExpandedVisible)) {
            if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Can't launch camera right now, mExpandedVisible: " +
                    mExpandedVisible);
            return;
        }
        if (!mDeviceInteractive) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                    "com.android.systemui:CAMERA_GESTURE");
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
        }
        vibrateForCameraGesture();
        if (!mStatusBarKeyguardViewManager.isShowing()) {
            startActivityDismissingKeyguard(KeyguardBottomAreaView.INSECURE_CAMERA_INTENT,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0);
        } else {
            if (!mDeviceInteractive) {
                // Avoid flickering of the scrim when we instant launch the camera and the bouncer
                // comes on.
                mGestureWakeLock.acquire(LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
            }
            if (isScreenTurningOnOrOn()) {
                if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Launching camera");
                if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                    mStatusBarKeyguardViewManager.reset(true /* hide */);
                }
                mNotificationPanel.launchCamera(mDeviceInteractive /* animate */, source);
                updateScrimController();
            } else {
                // We need to defer the camera launch until the screen comes on, since otherwise
                // we will dismiss us too early since we are waiting on an activity to be drawn and
                // incorrectly get notified because of the screen on event (which resumes and pauses
                // some activities)
                if (DEBUG_CAMERA_LIFT) Slog.d(TAG, "Deferring until screen turns on");
                mLaunchCameraOnScreenTurningOn = true;
            }
        }
    }

    boolean isCameraAllowedByAdmin() {
        if (mDevicePolicyManager.getCameraDisabled(null,
                mLockscreenUserManager.getCurrentUserId())) {
            return false;
        } else if (mStatusBarKeyguardViewManager == null ||
                (isKeyguardShowing() && isKeyguardSecure())) {
            // Check if the admin has disabled the camera specifically for the keyguard
            return (mDevicePolicyManager.
                    getKeyguardDisabledFeatures(null, mLockscreenUserManager.getCurrentUserId())
                    & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) == 0;
        }

        return true;
    }

    private boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    private boolean isScreenTurningOnOrOn() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_ON
                || mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
    }

    public void notifyBiometricAuthModeChanged() {
        updateDozing();
        updateScrimController();
    }

    @VisibleForTesting
    void updateScrimController() {
        Trace.beginSection("StatusBar#updateScrimController");

        // We don't want to end up in KEYGUARD state when we're unlocking with
        // fingerprint from doze. We should cross fade directly from black.
        boolean wakeAndUnlocking = mBiometricUnlockController.isWakeAndUnlock();

        // Do not animate the scrim expansion when triggered by the fingerprint sensor.
        mScrimController.setExpansionAffectsAlpha(
                !mBiometricUnlockController.isBiometricUnlock());

        boolean launchingAffordanceWithPreview =
                mNotificationPanel.isLaunchingAffordanceWithPreview();
        mScrimController.setLaunchingAffordanceWithPreview(launchingAffordanceWithPreview);

        if (mBouncerShowing) {
            // Bouncer needs the front scrim when it's on top of an activity,
            // tapping on a notification, editing QS or being dismissed by
            // FLAG_DISMISS_KEYGUARD_ACTIVITY.
            ScrimState state = mStatusBarKeyguardViewManager.bouncerNeedsScrimming()
                    ? ScrimState.BOUNCER_SCRIMMED : ScrimState.BOUNCER;
            mScrimController.transitionTo(state);
        } else if (isInLaunchTransition() || mLaunchCameraOnScreenTurningOn
                || launchingAffordanceWithPreview) {
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        } else if (mBrightnessMirrorVisible) {
            mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        } else if (isPulsing()) {
            mScrimController.transitionTo(ScrimState.PULSING,
                    mDozeScrimController.getScrimCallback());
        } else if (mDozing && !wakeAndUnlocking) {
            mScrimController.transitionTo(ScrimState.AOD);
        } else if (mIsKeyguard && !wakeAndUnlocking) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
        } else if (mBubbleController.isStackExpanded()) {
            mScrimController.transitionTo(ScrimState.BUBBLE_EXPANDED);
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

    @VisibleForTesting
    final class DozeServiceHost implements DozeHost {
        private final ArrayList<Callback> mCallbacks = new ArrayList<>();
        private boolean mAnimateWakeup;
        private boolean mAnimateScreenOff;
        private boolean mIgnoreTouchWhilePulsing;

        @Override
        public String toString() {
            return "PSB.DozeServiceHost[mCallbacks=" + mCallbacks.size() + "]";
        }

        public void firePowerSaveChanged(boolean active) {
            for (Callback callback : mCallbacks) {
                callback.onPowerSaveChanged(active);
            }
        }

        public void fireNotificationPulse() {
            for (Callback callback : mCallbacks) {
                callback.onNotificationAlerted();
            }
        }

        @Override
        public void addCallback(@NonNull Callback callback) {
            mCallbacks.add(callback);
        }

        @Override
        public void removeCallback(@NonNull Callback callback) {
            mCallbacks.remove(callback);
        }

        @Override
        public void startDozing() {
            if (!mDozingRequested) {
                mDozingRequested = true;
                DozeLog.traceDozing(mContext, mDozing);
                updateDozing();
                updateIsKeyguard();
            }
        }

        @Override
        public void pulseWhileDozing(@NonNull PulseCallback callback, int reason) {
            mScrimController.setPulseReason(reason);
            if (reason == DozeLog.PULSE_REASON_SENSOR_LONG_PRESS) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                        "com.android.systemui:LONG_PRESS");
                startAssist(new Bundle());
                return;
            }

            boolean passiveAuthInterrupt = reason == DozeLog.PULSE_REASON_NOTIFICATION;
            // Set the state to pulsing, so ScrimController will know what to do once we ask it to
            // execute the transition. The pulse callback will then be invoked when the scrims
            // are black, indicating that StatusBar is ready to present the rest of the UI.
            mPulsing = true;
            mDozeScrimController.pulse(new PulseCallback() {
                @Override
                public void onPulseStarted() {
                    callback.onPulseStarted();
                    updateNotificationPanelTouchState();
                    setPulsing(true);
                }

                @Override
                public void onPulseFinished() {
                    mPulsing = false;
                    callback.onPulseFinished();
                    updateNotificationPanelTouchState();
                    setPulsing(false);
                }

                private void setPulsing(boolean pulsing) {
                    mKeyguardViewMediator.setPulsing(pulsing);
                    mNotificationPanel.setPulsing(pulsing);
                    mVisualStabilityManager.setPulsing(pulsing);
                    mIgnoreTouchWhilePulsing = false;
                    if (mKeyguardUpdateMonitor != null && passiveAuthInterrupt) {
                        mKeyguardUpdateMonitor.onAuthInterruptDetected(pulsing /* active */);
                    }
                    updateScrimController();
                    mPulseExpansionHandler.setPulsing(pulsing);
                }
            }, reason);
            // DozeScrimController is in pulse state, now let's ask ScrimController to start
            // pulsing and draw the black frame, if necessary.
            updateScrimController();
        }

        @Override
        public void stopDozing() {
            if (mDozingRequested) {
                mDozingRequested = false;
                DozeLog.traceDozing(mContext, mDozing);
                updateDozing();
            }
        }

        @Override
        public void onIgnoreTouchWhilePulsing(boolean ignore) {
            if (ignore != mIgnoreTouchWhilePulsing) {
                DozeLog.tracePulseTouchDisabledByProx(mContext, ignore);
            }
            mIgnoreTouchWhilePulsing = ignore;
            if (isDozing() && ignore) {
                mStatusBarWindow.cancelCurrentTouch();
            }
        }

        @Override
        public void dozeTimeTick() {
            mNotificationPanel.dozeTimeTick();
            mNotificationIconAreaController.dozeTimeTick();
            if (mAmbientIndicationContainer instanceof DozeReceiver) {
                ((DozeReceiver) mAmbientIndicationContainer).dozeTimeTick();
            }
        }

        @Override
        public boolean isPowerSaveActive() {
            return mBatteryController.isAodPowerSave();
        }

        @Override
        public boolean isPulsingBlocked() {
            return mBiometricUnlockController.getMode()
                    == BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
        }

        @Override
        public boolean isProvisioned() {
            return mDeviceProvisionedController.isDeviceProvisioned()
                    && mDeviceProvisionedController.isCurrentUserSetup();
        }

        @Override
        public boolean isBlockingDoze() {
            if (mBiometricUnlockController.hasPendingAuthentication()) {
                Log.i(TAG, "Blocking AOD because fingerprint has authenticated");
                return true;
            }
            return false;
        }

        @Override
        public void extendPulse() {
            if (mDozeScrimController.isPulsing() && mAmbientPulseManager.hasNotifications()) {
                mAmbientPulseManager.extendPulse();
            } else {
                mDozeScrimController.extendPulse();
            }
        }

        @Override
        public void stopPulsing() {
            if (mDozeScrimController.isPulsing()) {
                mDozeScrimController.pulseOutNow();
            }
        }

        @Override
        public void setAnimateWakeup(boolean animateWakeup) {
            if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_AWAKE
                    || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_WAKING) {
                // Too late to change the wakeup animation.
                return;
            }
            mAnimateWakeup = animateWakeup;
        }

        @Override
        public void setAnimateScreenOff(boolean animateScreenOff) {
            mAnimateScreenOff = animateScreenOff;
        }

        @Override
        public void onSlpiTap(float screenX, float screenY) {
            if (screenX > 0 && screenY > 0 && mAmbientIndicationContainer != null
                && mAmbientIndicationContainer.getVisibility() == View.VISIBLE) {
                mAmbientIndicationContainer.getLocationOnScreen(mTmpInt2);
                float viewX = screenX - mTmpInt2[0];
                float viewY = screenY - mTmpInt2[1];
                if (0 <= viewX && viewX <= mAmbientIndicationContainer.getWidth()
                        && 0 <= viewY && viewY <= mAmbientIndicationContainer.getHeight()) {
                    dispatchTap(mAmbientIndicationContainer, viewX, viewY);
                }
            }
        }

        @Override
        public void setDozeScreenBrightness(int value) {
            mStatusBarWindowController.setDozeScreenBrightness(value);
        }

        @Override
        public void setAodDimmingScrim(float scrimOpacity) {
            mScrimController.setAodFrontScrimAlpha(scrimOpacity);
        }

        private void dispatchTap(View view, float x, float y) {
            long now = SystemClock.elapsedRealtime();
            dispatchTouchEvent(view, x, y, now, MotionEvent.ACTION_DOWN);
            dispatchTouchEvent(view, x, y, now, MotionEvent.ACTION_UP);
        }

        private void dispatchTouchEvent(View view, float x, float y, long now, int action) {
            MotionEvent ev = MotionEvent.obtain(now, now, action, x, y, 0 /* meta */);
            view.dispatchTouchEvent(ev);
            ev.recycle();
        }

        private boolean shouldAnimateWakeup() {
            return mAnimateWakeup;
        }

        public boolean shouldAnimateScreenOff() {
            return mAnimateScreenOff;
        }
    }

    public boolean shouldIgnoreTouch() {
        return isDozing() && mDozeServiceHost.mIgnoreTouchWhilePulsing;
    }

    // Begin Extra BaseStatusBar methods.

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;

    // all notifications
    protected ViewGroup mStackScroller;

    protected NotificationGroupManager mGroupManager;

    protected NotificationGroupAlertTransferHelper mGroupAlertTransferHelper;


    // for heads up notifications
    protected HeadsUpManagerPhone mHeadsUpManager;

    protected AmbientPulseManager mAmbientPulseManager = Dependency.get(AmbientPulseManager.class);

    // handling reordering
    protected VisualStabilityManager mVisualStabilityManager;

    protected AccessibilityManager mAccessibilityManager;

    protected boolean mDeviceInteractive;

    protected boolean mVisible;

    // mScreenOnFromKeyguard && mVisible.
    private boolean mVisibleToUser;

    protected DevicePolicyManager mDevicePolicyManager;
    protected PowerManager mPowerManager;
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    protected KeyguardManager mKeyguardManager;
    private DeviceProvisionedController mDeviceProvisionedController
            = Dependency.get(DeviceProvisionedController.class);

    protected NavigationBarController mNavigationBarController;

    // UI-specific methods

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;
    private IDreamManager mDreamManager;

    protected Display mDisplay;
    private int mDisplayId;

    protected Recents mRecents;

    protected NotificationShelf mNotificationShelf;
    protected EmptyShadeView mEmptyShadeView;

    protected AssistManager mAssistManager;

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
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                            true /* force */);
                    mContext.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_REDACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    );
                }
            }
        }
    };

    @Override
    public void collapsePanel(boolean animate) {
        if (animate) {
            boolean willCollapse = collapsePanel();
            if (!willCollapse) {
                runPostCollapseRunnables();
            }
        } else if (!mPresenter.isPresenterFullyCollapsed()) {
            instantCollapseNotificationPanel();
            visibilityChanged(false);
        } else {
            runPostCollapseRunnables();
        }
    }

    @Override
    public boolean collapsePanel() {
        if (!mNotificationPanel.isFullyCollapsed()) {
            // close the shade if it was open
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */,
                    true /* delayed */);
            visibilityChanged(false);

            return true;
        } else {
            return false;
        }
    }

    protected NotificationListener mNotificationListener;

    public void setNotificationSnoozed(StatusBarNotification sbn, SnoozeOption snoozeOption) {
        if (snoozeOption.getSnoozeCriterion() != null) {
            mNotificationListener.snoozeNotification(sbn.getKey(),
                    snoozeOption.getSnoozeCriterion().getId());
        } else {
            mNotificationListener.snoozeNotification(sbn.getKey(),
                    snoozeOption.getMinutesToSnoozeFor() * 60 * 1000);
        }
    }

    @Override
    public void toggleSplitScreen() {
        toggleSplitScreenMode(-1 /* metricsDockAction */, -1 /* metricsUndockAction */);
    }

    void awakenDreams() {
        Dependency.get(UiOffloadThread.class).submit(() -> {
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
     * Called when the notification panel layouts
     */
    public void onPanelLaidOut() {
        updateKeyguardMaxNotifications();
    }

    public void updateKeyguardMaxNotifications() {
        if (mState == StatusBarState.KEYGUARD) {
            // Since the number of notifications is determined based on the height of the view, we
            // need to update them.
            int maxBefore = mPresenter.getMaxNotificationsWhileLocked(false /* recompute */);
            int maxNotifications = mPresenter.getMaxNotificationsWhileLocked(true /* recompute */);
            if (maxBefore != maxNotifications) {
                mViewHierarchyManager.updateRowStates();
            }
        }
    }

    public void executeActionDismissingKeyguard(Runnable action, boolean afterKeyguardGone) {
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

            return collapsePanel();
        }, afterKeyguardGone);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(final PendingIntent intent) {
        startPendingIntentDismissingKeyguard(intent, null);
    }

    @Override
    public void startPendingIntentDismissingKeyguard(
            final PendingIntent intent, @Nullable final Runnable intentSentUiThreadCallback) {
        final boolean afterKeyguardGone = intent.isActivity()
                && PreviewInflater.wouldLaunchResolverActivity(mContext, intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());

        executeActionDismissingKeyguard(() -> {
            try {
                intent.send(null, 0, null, null, null, null, getActivityOptions(
                        null /* animationAdapter */));
            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.
                // Just log the exception message.
                Log.w(TAG, "Sending intent failed: " + e);

                // TODO: Dismiss Keyguard.
            }
            if (intent.isActivity()) {
                mAssistManager.hideAssist();
            }
            if (intentSentUiThreadCallback != null) {
                postOnUiThread(intentSentUiThreadCallback);
            }
        }, afterKeyguardGone);
    }

    private void postOnUiThread(Runnable runnable) {
        mMainThreadHandler.post(runnable);
    }

    public static Bundle getActivityOptions(@Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options;
        if (animationAdapter != null) {
            options = ActivityOptions.makeRemoteAnimation(animationAdapter);
        } else {
            options = ActivityOptions.makeBasic();
        }
        // Anything launched from the notification shade should always go into the secondary
        // split-screen windowing mode.
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        return options.toBundle();
    }

    protected void visibilityChanged(boolean visible) {
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
        if (mAssistManager != null) {
            mAssistManager.showDisclosure();
        }
    }

    public NotificationPanelView getPanel() {
        return mNotificationPanel;
    }

    @Override
    public void startAssist(Bundle args) {
        if (mAssistManager != null) {
            mAssistManager.startAssist(args);
        }
    }
    // End Extra BaseStatusBarMethods.

    public NotificationGutsManager getGutsManager() {
        return mGutsManager;
    }

    @Subcomponent
    public interface StatusBarInjector {
        void createStatusBar(StatusBar statusbar);
    }

    public @TransitionMode int getStatusBarMode() {
        return mStatusBarMode;
    }

}
