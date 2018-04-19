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
import static android.app.StatusBarManager.windowStateToString;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager
        .NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.PERMISSION_SELF;
import static com.android.systemui.statusbar.NotificationMediaManager.DEBUG_MEDIA;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_WARNING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.TaskStackBuilder;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
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
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.DateTimeView;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.MessagingGroup;
import com.android.internal.widget.MessagingMessage;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.EventLogTags;
import com.android.systemui.Interpolators;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.charging.WirelessChargingAnimation;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.fragments.ExtensionFragmentListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.car.CarQSFragment;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.AppTransitionFinishedEvent;
import com.android.systemui.recents.events.activity.UndockingTaskEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.AppOpsListener;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.FooterView;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.NotificationEntryManager;
import com.android.systemui.statusbar.NotificationGutsManager;
import com.android.systemui.statusbar.NotificationInfo;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLogger;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.AboveShelfObserver;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.phone.UnlockMethodCache.OnUnlockMethodChangedListener;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.PreviewInflater;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.volume.VolumeComponent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StatusBar extends SystemUI implements DemoMode,
        DragDownHelper.DragDownCallback, ActivityStarter, OnUnlockMethodChangedListener,
        OnHeadsUpChangedListener, CommandQueue.Callbacks,
        ColorExtractor.OnColorsChangedListener, ConfigurationListener, NotificationPresenter {
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

    private static final int STATUS_OR_NAV_TRANSIENT =
            View.STATUS_BAR_TRANSIENT | View.NAVIGATION_BAR_TRANSIENT;
    private static final long AUTOHIDE_TIMEOUT_MS = 2250;

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
    private static final boolean ONLY_CORE_APPS;

    /** If true, the lockscreen will show a distinct wallpaper */
    private static final boolean ENABLE_LOCKSCREEN_WALLPAPER = true;

    /**
     * Never let the alpha become zero for surfaces that draw with SRC - otherwise the RenderNode
     * won't draw anything and uninitialized memory will show through
     * if mScrimSrcModeEnabled. Note that 0.001 is rounded down to 0 in
     * libhwui.
     */
    private static final float SRC_MIN_ALPHA = 0.002f;

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
    protected FingerprintUnlockController mFingerprintUnlockController;
    private LightBarController mLightBarController;
    protected LockscreenWallpaper mLockscreenWallpaper;

    private int mNaturalBarHeight = -1;

    private final Point mCurrentDisplaySize = new Point();

    protected StatusBarWindowView mStatusBarWindow;
    protected PhoneStatusBarView mStatusBarView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    protected StatusBarWindowManager mStatusBarWindowManager;
    protected UnlockMethodCache mUnlockMethodCache;
    private DozeServiceHost mDozeServiceHost = new DozeServiceHost();
    private boolean mWakeUpComingFromTouch;
    private PointF mWakeUpTouchLocation;

    private final Object mQueueLock = new Object();

    protected StatusBarIconController mIconController;

    // expanded notifications
    protected NotificationPanelView mNotificationPanel; // the sliding/resizing panel within the notification window
    private TextView mNotificationPanelDebugText;

    // settings
    private QSPanel mQSPanel;

    // top bar
    private KeyguardStatusBarView mKeyguardStatusBar;
    private boolean mLeaveOpenOnKeyguardHide;
    KeyguardIndicationController mKeyguardIndicationController;

    // Keyguard is actually fading away now.
    protected boolean mKeyguardFadingAway;
    protected long mKeyguardFadingAwayDelay;
    protected long mKeyguardFadingAwayDuration;

    // RemoteInputView to be activated after unlock
    private View mPendingRemoteInputView;
    private View mPendingWorkRemoteInputView;

    private View mReportRejectedTouch;

    private int mMaxAllowedKeyguardNotifications;

    private boolean mExpandedVisible;

    private final int[] mAbsPos = new int[2];
    private final ArrayList<Runnable> mPostCollapseRunnables = new ArrayList<>();

    private NotificationGutsManager mGutsManager;
    protected NotificationLogger mNotificationLogger;
    protected NotificationEntryManager mEntryManager;
    protected NotificationViewHierarchyManager mViewHierarchyManager;
    protected AppOpsListener mAppOpsListener;
    protected KeyguardViewMediator mKeyguardViewMediator;
    private ZenModeController mZenController;

    /**
     * Helper that is responsible for showing the right toast when a disallowed activity operation
     * occurred. In pinned mode, we show instructions on how to break out of this mode, whilst in
     * fully locked mode we only show that unlocking is blocked.
     */
    private ScreenPinningNotify mScreenPinningNotify;

    // for disabling the status bar
    private int mDisabled1 = 0;
    private int mDisabled2 = 0;

    // tracking calls to View.setSystemUiVisibility()
    private int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;
    private final Rect mLastFullscreenStackBounds = new Rect();
    private final Rect mLastDockedStackBounds = new Rect();
    private final Rect mTmpRect = new Rect();

    // last value sent to window manager
    private int mLastDispatchedSystemUiVisibility = ~View.SYSTEM_UI_FLAG_VISIBLE;

    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();

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
    private boolean mAutohideSuspended;
    private int mStatusBarMode;
    private int mMaxKeyguardNotifications;

    private ViewMediatorCallback mKeyguardViewMediatorCallback;
    protected ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    private final Runnable mAutohide = () -> {
        int requested = mSystemUiVisibility & ~STATUS_OR_NAV_TRANSIENT;
        if (mSystemUiVisibility != requested) {
            notifyUiVisibilityChanged(requested);
        }
    };

    protected boolean mDozing;
    private boolean mDozingRequested;
    protected boolean mScrimSrcModeEnabled;

    protected BackDropView mBackdrop;
    protected ImageView mBackdropFront, mBackdropBack;
    protected final PorterDuffXfermode mSrcXferMode = new PorterDuffXfermode(PorterDuff.Mode.SRC);
    protected final PorterDuffXfermode mSrcOverXferMode =
            new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER);

    private NotificationMediaManager mMediaManager;
    protected NotificationLockscreenUserManager mLockscreenUserManager;
    protected NotificationRemoteInputManager mRemoteInputManager;

    private BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
            if (wallpaperManager == null) {
                Log.w(TAG, "WallpaperManager not available");
                return;
            }
            WallpaperInfo info = wallpaperManager.getWallpaperInfo();
            final boolean supportsAmbientMode = info != null &&
                    info.getSupportsAmbientMode();

            mStatusBarWindowManager.setWallpaperSupportsAmbientMode(supportsAmbientMode);
            mScrimController.setWallpaperSupportsAmbientMode(supportsAmbientMode);
        }
    };

    private Runnable mLaunchTransitionEndRunnable;
    protected boolean mLaunchTransitionFadingAway;
    private ExpandableNotificationRow mDraggedDownRow;
    private boolean mLaunchCameraOnScreenTurningOn;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
    private int mLastCameraLaunchSource;
    private PowerManager.WakeLock mGestureWakeLock;
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
            if (mKeyguardFadingAway) {
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
    private KeyguardMonitorImpl mKeyguardMonitor
            = (KeyguardMonitorImpl) Dependency.get(KeyguardMonitor.class);
    private BatteryController mBatteryController;
    protected boolean mPanelExpanded;
    private IOverlayManager mOverlayManager;
    private boolean mKeyguardRequested;
    private boolean mIsKeyguard;
    private LogMaker mStatusBarStateLog;
    private final LockscreenGestureLogger mLockscreenGestureLogger = new LockscreenGestureLogger();
    protected NotificationIconAreaController mNotificationIconAreaController;
    private boolean mReinflateNotificationsOnUserSwitched;
    protected boolean mClearAllEnabled;
    @Nullable private View mAmbientIndicationContainer;
    private SysuiColorExtractor mColorExtractor;
    private ScreenLifecycle mScreenLifecycle;
    @VisibleForTesting WakefulnessLifecycle mWakefulnessLifecycle;

    private final View.OnClickListener mGoToLockedShadeListener = v -> {
        if (mState == StatusBarState.KEYGUARD) {
            wakeUpIfDozing(SystemClock.uptimeMillis(), v);
            goToLockedShade(null);
        }
    };
    private boolean mNoAnimationOnNextBarModeChange;
    protected FalsingManager mFalsingManager;

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
                    if (dreaming) {
                        maybeEscalateHeadsUp();
                    }
                }
            };

    private NavigationBarFragment mNavigationBar;
    private View mNavigationBarView;
    protected ActivityLaunchAnimator mActivityLaunchAnimator;
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private boolean mVibrateOnOpening;
    private VibratorHelper mVibratorHelper;

    @Override
    public void start() {
        mGroupManager = Dependency.get(NotificationGroupManager.class);
        mVisualStabilityManager = Dependency.get(VisualStabilityManager.class);
        mNotificationLogger = Dependency.get(NotificationLogger.class);
        mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);
        mNotificationListener =  Dependency.get(NotificationListener.class);
        mGroupManager = Dependency.get(NotificationGroupManager.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mUserSwitcherController = Dependency.get(UserSwitcherController.class);
        mScreenLifecycle = Dependency.get(ScreenLifecycle.class);
        mScreenLifecycle.addObserver(mScreenObserver);
        mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mBatteryController = Dependency.get(BatteryController.class);
        mAssistManager = Dependency.get(AssistManager.class);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mLockscreenUserManager = Dependency.get(NotificationLockscreenUserManager.class);
        mGutsManager = Dependency.get(NotificationGutsManager.class);
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        mEntryManager = Dependency.get(NotificationEntryManager.class);
        mViewHierarchyManager = Dependency.get(NotificationViewHierarchyManager.class);
        mAppOpsListener = Dependency.get(AppOpsListener.class);
        mAppOpsListener.setUpWithPresenter(this, mEntryManager);
        mZenController = Dependency.get(ZenModeController.class);
        mKeyguardViewMediator = getComponent(KeyguardViewMediator.class);

        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        mColorExtractor.addOnColorsChangedListener(this);

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);

        mDisplay = mWindowManager.getDefaultDisplay();
        updateDisplaySize();

        Resources res = mContext.getResources();
        mVibrateOnOpening = mContext.getResources().getBoolean(
                R.bool.config_vibrateOnIconAnimation);
        mVibratorHelper = Dependency.get(VibratorHelper.class);
        mScrimSrcModeEnabled = res.getBoolean(R.bool.config_status_bar_scrim_behind_use_src);
        mClearAllEnabled = res.getBoolean(R.bool.config_enableNotificationsClearAll);

        DateTimeView.setReceiverHandler(Dependency.get(Dependency.TIME_TICK_HANDLER));
        putComponent(StatusBar.class, this);

        // start old BaseStatusBar.start().
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mRecents = getComponent(Recents.class);

        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mLockPatternUtils = new LockPatternUtils(mContext);

        mMediaManager.setUpWithPresenter(this, mEntryManager);

        // Connect in to the status bar manager service
        mCommandQueue = getComponent(CommandQueue.class);
        mCommandQueue.addCallbacks(this);

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
        mContext.registerReceiver(mWallpaperChangedReceiver, wallpaperChangedFilter);
        mWallpaperChangedReceiver.onReceive(mContext, null);

        mLockscreenUserManager.setUpWithPresenter(this, mEntryManager);
        mCommandQueue.disable(switches[0], switches[6], false /* animate */);
        setSystemUiVisibility(switches[1], switches[7], switches[8], 0xffffffff,
                fullscreenStackBounds, dockedStackBounds);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4], switches[5] != 0);

        // Set up the initial icon state
        int N = iconSlots.size();
        for (int i=0; i < N; i++) {
            mCommandQueue.setIcon(iconSlots.get(i), icons.get(i));
        }

        // Set up the initial notification state.
        mNotificationListener.setUpWithPresenter(this, mEntryManager);

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

        setHeadsUpUser(mLockscreenUserManager.getCurrentUserId());

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(BANNER_ACTION_CANCEL);
        internalFilter.addAction(BANNER_ACTION_SETUP);
        mContext.registerReceiver(mBannerActionBroadcastReceiver, internalFilter, PERMISSION_SELF,
                null);

        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        try {
            vrManager.registerListener(mVrStateCallbacks);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register VR mode state listener: " + e);
        }

        // end old BaseStatusBar.start().

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext, mIconController);
        mSignalPolicy = new StatusBarSignalPolicy(mContext, mIconController);

        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mUnlockMethodCache.addListener(this);
        startKeyguard();

        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateCallback);
        putComponent(DozeHost.class, mDozeServiceHost);

        mScreenPinningRequest = new ScreenPinningRequest(mContext);
        mFalsingManager = FalsingManager.getInstance(mContext);

        Dependency.get(ActivityStarterDelegate.class).setActivityStarterImpl(this);

        Dependency.get(ConfigurationController.class).addCallback(this);
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
        mActivityLaunchAnimator = new ActivityLaunchAnimator(mStatusBarWindow,
                this,
                mNotificationPanel,
                mStackScroller);
        mGutsManager.setUpWithPresenter(this, mEntryManager, mStackScroller, mCheckSaveListener,
                key -> {
                    try {
                        mBarService.onNotificationSettingsViewed(key);
                    } catch (RemoteException e) {
                        // if we're here we're dead
                    }
                });
        mNotificationLogger.setUpWithEntryManager(mEntryManager, mStackScroller);
        mNotificationPanel.setStatusBar(this);
        mNotificationPanel.setGroupManager(mGroupManager);
        mAboveShelfObserver = new AboveShelfObserver(mStackScroller);
        mAboveShelfObserver.setListener(mStatusBarWindow.findViewById(
                R.id.notification_container_parent));
        mKeyguardStatusBar = mStatusBarWindow.findViewById(R.id.keyguard_header);

        mNotificationIconAreaController = SystemUIFactory.getInstance()
                .createNotificationIconAreaController(context, this);
        inflateShelf();
        mNotificationIconAreaController.setupShelf(mNotificationShelf);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mNotificationIconAreaController);
        FragmentHostManager.get(mStatusBarWindow)
                .addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
                    CollapsedStatusBarFragment statusBarFragment =
                            (CollapsedStatusBarFragment) fragment;
                    statusBarFragment.initNotificationIconArea(mNotificationIconAreaController);
                    mStatusBarView = (PhoneStatusBarView) fragment.getView();
                    mStatusBarView.setBar(this);
                    mStatusBarView.setPanel(mNotificationPanel);
                    mStatusBarView.setScrimController(mScrimController);
                    mStatusBarView.setBouncerShowing(mBouncerShowing);
                    if (mHeadsUpAppearanceController != null) {
                        // This view is being recreated, let's destroy the old one
                        mHeadsUpAppearanceController.destroy();
                    }
                    mHeadsUpAppearanceController = new HeadsUpAppearanceController(
                            mNotificationIconAreaController, mHeadsUpManager, mStatusBarWindow);
                    setAreThereNotifications();
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
        mHeadsUpManager.addListener(mVisualStabilityManager);
        mNotificationPanel.setHeadsUpManager(mHeadsUpManager);
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
        putComponent(HeadsUpManager.class, mHeadsUpManager);

        mEntryManager.setUpWithPresenter(this, mStackScroller, this, mHeadsUpManager);
        mViewHierarchyManager.setUpWithPresenter(this, mEntryManager, mStackScroller);

        if (MULTIUSER_DEBUG) {
            mNotificationPanelDebugText = mNotificationPanel.findViewById(R.id.header_debug_info);
            mNotificationPanelDebugText.setVisibility(View.VISIBLE);
        }

        try {
            boolean showNav = mWindowManagerService.hasNavigationBar();
            if (DEBUG) Log.v(TAG, "hasNavigationBar=" + showNav);
            if (showNav) {
                createNavigationBar();
            }
        } catch (RemoteException ex) {
            // no window manager? good luck with that
        }
        mScreenPinningNotify = new ScreenPinningNotify(mContext);
        mStackScroller.setLongPressListener(mEntryManager.getNotificationLongClicker());
        mStackScroller.setStatusBar(this);
        mStackScroller.setGroupManager(mGroupManager);
        mStackScroller.setHeadsUpManager(mHeadsUpManager);
        mGroupManager.setOnGroupChangeListener(mStackScroller);
        mVisualStabilityManager.setVisibilityLocationProvider(mStackScroller);

        inflateEmptyShadeView();
        inflateFooterView();

        mBackdrop = mStatusBarWindow.findViewById(R.id.backdrop);
        mBackdropFront = mBackdrop.findViewById(R.id.backdrop_front);
        mBackdropBack = mBackdrop.findViewById(R.id.backdrop_back);

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

        // set the initial view visibility
        setAreThereNotifications();

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

        mLightBarController = Dependency.get(LightBarController.class);
        if (mNavigationBar != null) {
            mNavigationBar.setLightBarController(mLightBarController);
        }

        ScrimView scrimBehind = mStatusBarWindow.findViewById(R.id.scrim_behind);
        ScrimView scrimInFront = mStatusBarWindow.findViewById(R.id.scrim_in_front);
        mScrimController = SystemUIFactory.getInstance().createScrimController(
                scrimBehind, scrimInFront, mLockscreenWallpaper,
                (state, alpha, color) -> mLightBarController.setScrimState(state, alpha, color),
                scrimsVisible -> {
                    if (mStatusBarWindowManager != null) {
                        mStatusBarWindowManager.setScrimsVisibility(scrimsVisible);
                    }
                }, DozeParameters.getInstance(mContext),
                mContext.getSystemService(AlarmManager.class));
        if (mScrimSrcModeEnabled) {
            Runnable runnable = () -> {
                boolean asSrc = mBackdrop.getVisibility() != View.VISIBLE;
                mScrimController.setDrawBehindAsSrc(asSrc);
                mStackScroller.setDrawBackgroundAsSrc(asSrc);
            };
            mBackdrop.setOnVisibilityChangedRunnable(runnable);
            runnable.run();
        }
        mStackScroller.setScrimController(mScrimController);
        mDozeScrimController = new DozeScrimController(mScrimController, context,
                DozeParameters.getInstance(context));

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
                            .withFeature(PackageManager.FEATURE_AUTOMOTIVE, CarQSFragment::new)
                            .withDefault(QSFragment::new)
                            .build());
            final QSTileHost qsh = SystemUIFactory.getInstance().createQSTileHost(mContext, this,
                    mIconController);
            mBrightnessMirrorController = new BrightnessMirrorController(mStatusBarWindow,
                    (visible) -> {
                        mBrightnessMirrorVisible = visible;
                        updateScrimController();
                    });
            fragmentHostManager.addTagListener(QS.TAG, (tag, f) -> {
                QS qs = (QS) f;
                if (qs instanceof QSFragment) {
                    ((QSFragment) qs).setHost(qsh);
                    mQSPanel = ((QSFragment) qs).getQsPanel();
                    mQSPanel.setBrightnessMirror(mBrightnessMirrorController);
                    mKeyguardStatusBar.setQSPanel(mQSPanel);
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

    protected void createNavigationBar() {
        mNavigationBarView = NavigationBarFragment.create(mContext, (tag, fragment) -> {
            mNavigationBar = (NavigationBarFragment) fragment;
            if (mLightBarController != null) {
                mNavigationBar.setLightBarController(mLightBarController);
            }
            mNavigationBar.setCurrentSysuiVisibility(mSystemUiVisibility);
        });
    }

    /**
     * Returns the {@link android.view.View.OnTouchListener} that will be invoked when the
     * background window of the status bar is clicked.
     */
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        return (v, event) -> {
            checkUserAutohide(event);
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
        mNotificationShelf.setOnActivatedListener(this);
        mStackScroller.setShelf(mNotificationShelf);
        mNotificationShelf.setOnClickListener(mGoToLockedShadeListener);
        mNotificationShelf.setStatusBarState(mState);
    }

    public void onDensityOrFontScaleChanged() {
        MessagingMessage.dropCache();
        MessagingGroup.dropCache();
        // start old BaseStatusBar.onDensityOrFontScaleChanged().
        if (!KeyguardUpdateMonitor.getInstance(mContext).isSwitchingUser()) {
            mEntryManager.updateNotificationsOnDensityOrFontScaleChanged();
        } else {
            mReinflateNotificationsOnUserSwitched = true;
        }
        // end old BaseStatusBar.onDensityOrFontScaleChanged().
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

        reevaluateStyles();
    }

    private void onThemeChanged() {
        reevaluateStyles();

        // Clock and bottom icons
        mNotificationPanel.onThemeChanged();
        // The status bar on the keyguard is a special layout.
        if (mKeyguardStatusBar != null) mKeyguardStatusBar.onThemeChanged();
        // Recreate Indication controller because internal references changed
        mKeyguardIndicationController =
                SystemUIFactory.getInstance().createKeyguardIndicationController(mContext,
                        mStatusBarWindow.findViewById(R.id.keyguard_indication_area),
                        mNotificationPanel.getLockIcon());
        mNotificationPanel.setKeyguardIndicationController(mKeyguardIndicationController);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mKeyguardIndicationController.setVisible(mState == StatusBarState.KEYGUARD);
        mKeyguardIndicationController.setDozing(mDozing);
        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.onThemeChanged();
        }
        if (mAmbientIndicationContainer instanceof AutoReinflateContainer) {
            ((AutoReinflateContainer) mAmbientIndicationContainer).inflateLayout();
        }
    }

    protected void reevaluateStyles() {
        inflateFooterView();
        updateFooter();
        inflateEmptyShadeView();
        updateEmptyShadeView();
    }

    @Override
    public void onOverlayChanged() {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onOverlayChanged();
        }
    }

    private void inflateEmptyShadeView() {
        if (mStackScroller == null) {
            return;
        }
        mEmptyShadeView = (EmptyShadeView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_no_notifications, mStackScroller, false);
        mEmptyShadeView.setText(R.string.empty_shade_text);
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
    }

    private void inflateFooterView() {
        if (mStackScroller == null) {
            return;
        }

        mFooterView = (FooterView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_notification_footer, mStackScroller, false);
        mFooterView.setDismissButtonClickListener(v -> {
            mMetricsLogger.action(MetricsEvent.ACTION_DISMISS_ALL_NOTES);
            clearAllNotifications();
        });
        mFooterView.setManageButtonClickListener(v -> {
            manageNotifications();
        });
        mStackScroller.setFooterView(mFooterView);
    }

    protected void createUserSwitcher() {
        mKeyguardUserSwitcher = new KeyguardUserSwitcher(mContext,
                mStatusBarWindow.findViewById(R.id.keyguard_user_switcher), mKeyguardStatusBar,
                mNotificationPanel);
    }

    protected void inflateStatusBarWindow(Context context) {
        mStatusBarWindow = (StatusBarWindowView) View.inflate(context,
                R.layout.super_status_bar, null);
    }

    public void manageNotifications() {
        Intent intent = new Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS);
        startActivity(intent, true, true, Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    public void clearAllNotifications() {
        // animate-swipe all dismissable notifications, then animate the shade closed
        int numChildren = mStackScroller.getChildCount();

        final ArrayList<View> viewsToHide = new ArrayList<>(numChildren);
        final ArrayList<ExpandableNotificationRow> viewsToRemove = new ArrayList<>(numChildren);
        for (int i = 0; i < numChildren; i++) {
            final View child = mStackScroller.getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                boolean parentVisible = false;
                boolean hasClipBounds = child.getClipBounds(mTmpRect);
                if (mStackScroller.canChildBeDismissed(child)) {
                    viewsToRemove.add(row);
                    if (child.getVisibility() == View.VISIBLE
                            && (!hasClipBounds || mTmpRect.height() > 0)) {
                        viewsToHide.add(child);
                        parentVisible = true;
                    }
                } else if (child.getVisibility() == View.VISIBLE
                        && (!hasClipBounds || mTmpRect.height() > 0)) {
                    parentVisible = true;
                }
                List<ExpandableNotificationRow> children = row.getNotificationChildren();
                if (children != null) {
                    for (ExpandableNotificationRow childRow : children) {
                        viewsToRemove.add(childRow);
                        if (parentVisible && row.areChildrenExpanded()
                                && mStackScroller.canChildBeDismissed(childRow)) {
                            hasClipBounds = childRow.getClipBounds(mTmpRect);
                            if (childRow.getVisibility() == View.VISIBLE
                                    && (!hasClipBounds || mTmpRect.height() > 0)) {
                                viewsToHide.add(childRow);
                            }
                        }
                    }
                }
            }
        }
        if (viewsToRemove.isEmpty()) {
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
            return;
        }

        addPostCollapseAction(() -> {
            mStackScroller.setDismissAllInProgress(false);
            for (ExpandableNotificationRow rowToRemove : viewsToRemove) {
                if (mStackScroller.canChildBeDismissed(rowToRemove)) {
                    mEntryManager.removeNotification(rowToRemove.getEntry().key, null);
                } else {
                    rowToRemove.resetTranslation();
                }
            }
            try {
                mBarService.onClearAllNotifications(mLockscreenUserManager.getCurrentUserId());
            } catch (Exception ex) {
            }
        });

        performDismissAllAnimations(viewsToHide);

    }

    private void performDismissAllAnimations(ArrayList<View> hideAnimatedList) {
        Runnable animationFinishAction = () -> {
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
        };

        if (hideAnimatedList.isEmpty()) {
            animationFinishAction.run();
            return;
        }

        // let's disable our normal animations
        mStackScroller.setDismissAllInProgress(true);

        // Decrease the delay for every row we animate to give the sense of
        // accelerating the swipes
        int rowDelayDecrement = 10;
        int currentDelay = 140;
        int totalDelay = 180;
        int numItems = hideAnimatedList.size();
        for (int i = numItems - 1; i >= 0; i--) {
            View view = hideAnimatedList.get(i);
            Runnable endRunnable = null;
            if (i == 0) {
                endRunnable = animationFinishAction;
            }
            mStackScroller.dismissViewAnimated(view, endRunnable, totalDelay, 260);
            currentDelay = Math.max(50, currentDelay - rowDelayDecrement);
            totalDelay += currentDelay;
        }
    }

    protected void startKeyguard() {
        Trace.beginSection("StatusBar#startKeyguard");
        KeyguardViewMediator keyguardViewMediator = getComponent(KeyguardViewMediator.class);
        mFingerprintUnlockController = new FingerprintUnlockController(mContext,
                mDozeScrimController, keyguardViewMediator,
                mScrimController, this, UnlockMethodCache.getInstance(mContext));
        mStatusBarKeyguardViewManager = keyguardViewMediator.registerStatusBar(this,
                getBouncerContainer(), mNotificationPanel, mFingerprintUnlockController);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mFingerprintUnlockController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mRemoteInputManager.getController().addCallback(mStatusBarKeyguardViewManager);

        mKeyguardViewMediatorCallback = keyguardViewMediator.getViewMediatorCallback();
        mLightBarController.setFingerprintUnlockController(mFingerprintUnlockController);
        Dependency.get(KeyguardDismissUtil.class).setDismissHandler(
                this::dismissKeyguardThenExecute);
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
            return mRecents.splitPrimaryTask(NavigationBarGestureHelper.DRAG_MODE_NONE,
                    ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, null, metricsDockAction);
        } else {
            Divider divider = getComponent(Divider.class);
            if (divider != null && divider.isMinimized() && !divider.isHomeStackResizable()) {
                // Undocking from the minimized state is not supported
                return false;
            } else {
                EventBus.getDefault().send(new UndockingTaskEvent());
                if (metricsUndockAction != -1) {
                    mMetricsLogger.action(metricsUndockAction);
                }
            }
        }
        return true;
    }

    @Override
    public void onPerformRemoveNotification(StatusBarNotification n) {
        if (mStackScroller.hasPulsingNotifications() &&
                    !mHeadsUpManager.hasHeadsUpNotifications()) {
            // We were showing a pulse for a notification, but no notifications are pulsing anymore.
            // Finish the pulse.
            mDozeScrimController.pulseOutNow();
        }
    }

    @Override
    public void updateNotificationViews() {
        // The function updateRowStates depends on both of these being non-null, so check them here.
        // We may be called before they are set from DeviceProvisionedController's callback.
        if (mStackScroller == null || mScrimController == null) return;

        // Do not modify the notifications during collapse.
        if (isCollapsing()) {
            addPostCollapseAction(this::updateNotificationViews);
            return;
        }

        mViewHierarchyManager.updateNotificationViews();

        updateSpeedBumpIndex();
        updateFooter();
        updateEmptyShadeView();

        updateQsExpansionEnabled();

        // Let's also update the icons
        mNotificationIconAreaController.updateNotificationIcons();
    }

    @Override
    public void onNotificationAdded(Entry shadeEntry) {
        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
    }

    @Override
    public void onNotificationUpdated(StatusBarNotification notification) {
        setAreThereNotifications();
    }

    @Override
    public void onNotificationRemoved(String key, StatusBarNotification old) {
        if (SPEW) Log.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            if (CLOSE_PANEL_WHEN_EMPTIED && !hasActiveNotifications()
                    && !mNotificationPanel.isTracking() && !mNotificationPanel.isQsExpanded()) {
                if (mState == StatusBarState.SHADE) {
                    animateCollapsePanels();
                } else if (mState == StatusBarState.SHADE_LOCKED && !isCollapsing()) {
                    goToKeyguard();
                }
            }
        }
        setAreThereNotifications();
    }

    /**
     * Disable QS if device not provisioned.
     * If the user switcher is simple then disable QS during setup because
     * the user intends to use the lock screen user switcher, QS in not needed.
     */
    private void updateQsExpansionEnabled() {
        mNotificationPanel.setQsExpansionEnabled(isDeviceProvisioned()
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

    @VisibleForTesting
    protected void updateFooter() {
        boolean showFooterView = mState != StatusBarState.KEYGUARD
                && mEntryManager.getNotificationData().getActiveNotifications().size() != 0
                && !mRemoteInputManager.getController().isRemoteInputActive();
        boolean showDismissView = mClearAllEnabled && mState != StatusBarState.KEYGUARD
                && hasActiveClearableNotifications();

        mStackScroller.updateFooterView(showFooterView, showDismissView);
    }

    /**
     * Return whether there are any clearable notifications
     */
    private boolean hasActiveClearableNotifications() {
        int childCount = mStackScroller.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mStackScroller.getChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            if (((ExpandableNotificationRow) child).canViewBeDismissed()) {
                    return true;
            }
        }
        return false;
    }

    private void updateEmptyShadeView() {
        boolean showEmptyShadeView =
                mState != StatusBarState.KEYGUARD &&
                        mEntryManager.getNotificationData().getActiveNotifications().size() == 0;
        mNotificationPanel.showEmptyShadeView(showEmptyShadeView);
    }

    private void updateSpeedBumpIndex() {
        int speedBumpIndex = 0;
        int currentIndex = 0;
        final int N = mStackScroller.getChildCount();
        for (int i = 0; i < N; i++) {
            View view = mStackScroller.getChildAt(i);
            if (view.getVisibility() == View.GONE || !(view instanceof ExpandableNotificationRow)) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            currentIndex++;
            if (!mEntryManager.getNotificationData().isAmbient(
                    row.getStatusBarNotification().getKey())) {
                speedBumpIndex = currentIndex;
            }
        }
        boolean noAmbient = speedBumpIndex == N;
        mStackScroller.updateSpeedBumpIndex(speedBumpIndex, noAmbient);
    }

    public static boolean isTopLevelChild(Entry entry) {
        return entry.row.getParent() instanceof NotificationStackScrollLayout;
    }

    public boolean areNotificationsHidden() {
        return mZenController.areNotificationsHiddenInShade();
    }

    public void requestNotificationUpdate() {
        mEntryManager.updateNotifications();
    }

    protected void setAreThereNotifications() {

        if (SPEW) {
            final boolean clearable = hasActiveNotifications() &&
                    hasActiveClearableNotifications();
            Log.d(TAG, "setAreThereNotifications: N=" +
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


    /**
     * Hide the album artwork that is fading out and release its bitmap.
     */
    protected final Runnable mHideBackdropFront = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: removing fade layer");
            }
            mBackdropFront.setVisibility(View.INVISIBLE);
            mBackdropFront.animate().cancel();
            mBackdropFront.setImageDrawable(null);
        }
    };

    // TODO: Move this to NotificationMediaManager.
    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    @Override
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        Trace.beginSection("StatusBar#updateMediaMetaData");
        if (!SHOW_LOCKSCREEN_MEDIA_ARTWORK) {
            Trace.endSection();
            return;
        }

        if (mBackdrop == null) {
            Trace.endSection();
            return; // called too early
        }

        if (mLaunchTransitionFadingAway) {
            mBackdrop.setVisibility(View.INVISIBLE);
            Trace.endSection();
            return;
        }

        MediaMetadata mediaMetadata = mMediaManager.getMediaMetadata();

        if (DEBUG_MEDIA) {
            Log.v(TAG, "DEBUG_MEDIA: updating album art for notification "
                    + mMediaManager.getMediaNotificationKey()
                    + " metadata=" + mediaMetadata
                    + " metaDataChanged=" + metaDataChanged
                    + " state=" + mState);
        }

        Drawable artworkDrawable = null;
        if (mediaMetadata != null) {
            Bitmap artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (artworkBitmap == null) {
                artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                // might still be null
            }
            if (artworkBitmap != null) {
                artworkDrawable = new BitmapDrawable(mBackdropBack.getResources(), artworkBitmap);
            }
        }
        boolean allowWhenShade = false;
        if (ENABLE_LOCKSCREEN_WALLPAPER && artworkDrawable == null) {
            Bitmap lockWallpaper = mLockscreenWallpaper.getBitmap();
            if (lockWallpaper != null) {
                artworkDrawable = new LockscreenWallpaper.WallpaperDrawable(
                        mBackdropBack.getResources(), lockWallpaper);
                // We're in the SHADE mode on the SIM screen - yet we still need to show
                // the lockscreen wallpaper in that mode.
                allowWhenShade = mStatusBarKeyguardViewManager != null
                        && mStatusBarKeyguardViewManager.isShowing();
            }
        }

        boolean hideBecauseOccluded = mStatusBarKeyguardViewManager != null
                && mStatusBarKeyguardViewManager.isOccluded();

        final boolean hasArtwork = artworkDrawable != null;

        if ((hasArtwork || DEBUG_MEDIA_FAKE_ARTWORK) && !mDozing
                && (mState != StatusBarState.SHADE || allowWhenShade)
                && mFingerprintUnlockController.getMode()
                        != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && !hideBecauseOccluded) {
            // time to show some art!
            if (mBackdrop.getVisibility() != View.VISIBLE) {
                mBackdrop.setVisibility(View.VISIBLE);
                if (allowEnterAnimation) {
                    mBackdrop.setAlpha(SRC_MIN_ALPHA);
                    mBackdrop.animate().alpha(1f);
                } else {
                    mBackdrop.animate().cancel();
                    mBackdrop.setAlpha(1f);
                }
                mStatusBarWindowManager.setBackdropShowing(true);
                mColorExtractor.setMediaBackdropVisible(true);
                metaDataChanged = true;
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading in album artwork");
                }
            }
            if (metaDataChanged) {
                if (mBackdropBack.getDrawable() != null) {
                    Drawable drawable =
                            mBackdropBack.getDrawable().getConstantState()
                                    .newDrawable(mBackdropFront.getResources()).mutate();
                    mBackdropFront.setImageDrawable(drawable);
                    if (mScrimSrcModeEnabled) {
                        mBackdropFront.getDrawable().mutate().setXfermode(mSrcOverXferMode);
                    }
                    mBackdropFront.setAlpha(1f);
                    mBackdropFront.setVisibility(View.VISIBLE);
                } else {
                    mBackdropFront.setVisibility(View.INVISIBLE);
                }

                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    final int c = 0xFF000000 | (int)(Math.random() * 0xFFFFFF);
                    Log.v(TAG, String.format("DEBUG_MEDIA: setting new color: 0x%08x", c));
                    mBackdropBack.setBackgroundColor(0xFFFFFFFF);
                    mBackdropBack.setImageDrawable(new ColorDrawable(c));
                } else {
                    mBackdropBack.setImageDrawable(artworkDrawable);
                }
                if (mScrimSrcModeEnabled) {
                    mBackdropBack.getDrawable().mutate().setXfermode(mSrcXferMode);
                }

                if (mBackdropFront.getVisibility() == View.VISIBLE) {
                    if (DEBUG_MEDIA) {
                        Log.v(TAG, "DEBUG_MEDIA: Crossfading album artwork from "
                                + mBackdropFront.getDrawable()
                                + " to "
                                + mBackdropBack.getDrawable());
                    }
                    mBackdropFront.animate()
                            .setDuration(250)
                            .alpha(0f).withEndAction(mHideBackdropFront);
                }
            }
        } else {
            // need to hide the album art, either because we are unlocked, on AOD
            // or because the metadata isn't there to support it
            if (mBackdrop.getVisibility() != View.GONE) {
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading out album artwork");
                }
                mColorExtractor.setMediaBackdropVisible(false);
                boolean cannotAnimateDoze = mDozing && !ScrimState.AOD.getAnimateChange();
                if (mFingerprintUnlockController.getMode()
                        == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                        || hideBecauseOccluded || cannotAnimateDoze) {

                    // We are unlocking directly - no animation!
                    mBackdrop.setVisibility(View.GONE);
                    mBackdropBack.setImageDrawable(null);
                    mStatusBarWindowManager.setBackdropShowing(false);
                } else {
                    mStatusBarWindowManager.setBackdropShowing(false);
                    mBackdrop.animate()
                            .alpha(SRC_MIN_ALPHA)
                            .setInterpolator(Interpolators.ACCELERATE_DECELERATE)
                            .setDuration(300)
                            .setStartDelay(0)
                            .withEndAction(() -> {
                                mBackdrop.setVisibility(View.GONE);
                                mBackdropFront.animate().cancel();
                                mBackdropBack.setImageDrawable(null);
                                mHandler.post(mHideBackdropFront);
                            });
                    if (mKeyguardFadingAway) {
                        mBackdrop.animate()
                                // Make it disappear faster, as the focus should be on the activity
                                // behind.
                                .setDuration(mKeyguardFadingAwayDuration / 2)
                                .setStartDelay(mKeyguardFadingAwayDelay)
                                .setInterpolator(Interpolators.LINEAR)
                                .start();
                    }
                }
            }
        }
        Trace.endSection();
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
    public void disable(int state1, int state2, boolean animate) {
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
            mEntryManager.setDisableNotificationAlerts(
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

    /**
     * Reapplies the disable flags as last requested by StatusBarManager.
     *
     * This needs to be called if state used by {@link #adjustDisableFlags} changes.
     */
    public void recomputeDisableFlags(boolean animate) {
        mCommandQueue.recomputeDisableFlags(animate);
    }

    protected H createHandler() {
        return new StatusBar.H();
    }

    private void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade,
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
        mStatusBarWindowManager.setQsExpanded(expanded);
        mNotificationPanel.setStatusAccessibilityImportance(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public boolean isGoingToNotificationShade() {
        return mLeaveOpenOnKeyguardHide;
    }

    public boolean isWakeUpComingFromTouch() {
        return mWakeUpComingFromTouch;
    }

    public boolean isFalsingThresholdNeeded() {
        return getBarState() == StatusBarState.KEYGUARD;
    }

    @Override
    public boolean isDozing() {
        return mDozing && mStackScroller.isFullyDark();
    }

    @Override
    public boolean shouldPeek(Entry entry, StatusBarNotification sbn) {
        if (mIsOccluded && !isDozing()) {
            boolean devicePublic = mLockscreenUserManager.
                    isLockscreenPublicMode(mLockscreenUserManager.getCurrentUserId());
            boolean userPublic = devicePublic
                    || mLockscreenUserManager.isLockscreenPublicMode(sbn.getUserId());
            boolean needsRedaction = mLockscreenUserManager.needsRedaction(entry);
            if (userPublic && needsRedaction) {
                return false;
            }
        }

        if (sbn.getNotification().fullScreenIntent != null) {
            if (mAccessibilityManager.isTouchExplorationEnabled()) {
                if (DEBUG) Log.d(TAG, "No peeking: accessible fullscreen: " + sbn.getKey());
                return false;
            } else if (isDozing()) {
                // We never want heads up when we are dozing.
                return false;
            } else {
                // we only allow head-up on the lockscreen if it doesn't have a fullscreen intent
                return !mStatusBarKeyguardViewManager.isShowing()
                        || mStatusBarKeyguardViewManager.isOccluded();
            }
        }
        return true;
    }

    @Override  // NotificationData.Environment
    public String getCurrentMediaNotificationKey() {
        return mMediaManager.getMediaNotificationKey();
    }

    public boolean isScrimSrcModeEnabled() {
        return mScrimSrcModeEnabled;
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
            mStatusBarWindowManager.setHeadsUpShowing(true);
            mStatusBarWindowManager.setForceStatusBarVisible(true);
            if (mNotificationPanel.isFullyCollapsed()) {
                // We need to ensure that the touchable region is updated before the window will be
                // resized, in order to not catch any touches. A layout will ensure that
                // onComputeInternalInsets will be called and after that we can resize the layout. Let's
                // make sure that the window stays small for one frame until the touchableRegion is set.
                mNotificationPanel.requestLayout();
                mStatusBarWindowManager.setForceWindowCollapsed(true);
                mNotificationPanel.post(() -> {
                    mStatusBarWindowManager.setForceWindowCollapsed(false);
                });
            }
        } else {
            if (!mNotificationPanel.isFullyCollapsed() || mNotificationPanel.isTracking()) {
                // We are currently tracking or is open and the shade doesn't need to be kept
                // open artificially.
                mStatusBarWindowManager.setHeadsUpShowing(false);
            } else {
                // we need to keep the panel open artificially, let's wait until the animation
                // is finished.
                mHeadsUpManager.setHeadsUpGoingAway(true);
                mStackScroller.runAfterAnimationFinished(() -> {
                    if (!mHeadsUpManager.hasPinnedHeadsUp()) {
                        mStatusBarWindowManager.setHeadsUpShowing(false);
                        mHeadsUpManager.setHeadsUpGoingAway(false);
                    }
                    mRemoteInputManager.removeRemoteInputEntriesKeptUntilCollapsed();
                });
            }
        }
    }

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
        dismissVolumeDialog();
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {
    }

    @Override
    public void onHeadsUpStateChanged(Entry entry, boolean isHeadsUp) {
        mEntryManager.onHeadsUpStateChanged(entry, isHeadsUp);

        if (isHeadsUp) {
            mDozeServiceHost.fireNotificationHeadsUp();
        }
    }

    protected void setHeadsUpUser(int newUserId) {
        if (mHeadsUpManager != null) {
            mHeadsUpManager.setUser(newUserId);
        }
    }

    public boolean isKeyguardCurrentlySecure() {
        return !mUnlockMethodCache.canSkipBouncer();
    }

    public void setPanelExpanded(boolean isExpanded) {
        mPanelExpanded = isExpanded;
        updateHideIconsForBouncer(false /* animate */);
        mStatusBarWindowManager.setPanelExpanded(isExpanded);
        mVisualStabilityManager.setPanelExpanded(isExpanded);
        if (isExpanded && getBarState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from setExpandedHeight");
            }
            clearNotificationEffects();
        }

        if (!isExpanded) {
            mRemoteInputManager.removeRemoteInputEntriesKeptUntilCollapsed();
        }
    }

    public NotificationStackScrollLayout getNotificationScrollLayout() {
        return mStackScroller;
    }

    public boolean isPulsing() {
        return mDozeScrimController != null && mDozeScrimController.isPulsing();
    }

    public boolean isLaunchTransitionFadingAway() {
        return mLaunchTransitionFadingAway;
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        return mNotificationPanel.hideStatusBarIconsWhenExpanded();
    }

    @Override
    public void onColorsChanged(ColorExtractor extractor, int which) {
        updateTheme();
    }

    public boolean isUsingDarkTheme() {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = mOverlayManager.getOverlayInfo("com.android.systemui.theme.dark",
                    mLockscreenUserManager.getCurrentUserId());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return themeInfo != null && themeInfo.isEnabled();
    }

    @Nullable
    public View getAmbientIndicationContainer() {
        return mAmbientIndicationContainer;
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
        boolean hideBecauseApp = mTopHidesStatusBar && mIsOccluded;
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
                    recomputeDisableFlags(true);
                }, 500);
            } else {
                recomputeDisableFlags(animate);
            }
        }
        if (shouldHideIconsForBouncer) {
            mBouncerWasShowingWhenHidden = mBouncerShowing;
        }
    }

    public void onLaunchAnimationCancelled() {
        if (!isCollapsing()) {
            onClosingFinished();
        }
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
        if (!panelsEnabled() || !mKeyguardMonitor.isDeviceInteractive()
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
                mNotificationPanel.flingSettings(0 /* velocity */, true /* expand */);
                mMetricsLogger.count(NotificationPanelView.COUNTER_PANEL_OPEN_QS, 1);
            }
        }

    }

    @Override
    public void showPinningEnterExitToast(boolean entering) {
        if (entering) {
            mScreenPinningNotify.showPinningStartToast();
        } else {
            mScreenPinningNotify.showPinningExitToast();
        }
    }

    @Override
    public void showPinningEscapeToast() {
        mScreenPinningNotify.showEscapeToast(getNavigationBarView() == null
                || getNavigationBarView().isRecentsButtonVisible());
    }

    boolean panelsEnabled() {
        return (mDisabled1 & StatusBarManager.DISABLE_EXPAND) == 0
                && (mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) == 0
                && !ONLY_CORE_APPS;
    }

    void makeExpandedVisible(boolean force) {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (!force && (mExpandedVisible || !panelsEnabled())) {
            return;
        }

        mExpandedVisible = true;

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        mStatusBarWindowManager.setPanelVisible(true);

        visibilityChanged(true);
        recomputeDisableFlags(!force /* animate */);
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

    @Override
    public void animateCollapsePanels(int flags) {
        animateCollapsePanels(flags, false /* force */, false /* delayed */,
                1.0f /* speedUpFactor */);
    }

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
            mStatusBarWindowManager.setStatusBarFocusable(false);

            mStatusBarWindow.cancelExpandHelper();
            mStatusBarView.collapsePanel(true /* animate */, delayed, speedUpFactor);
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

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
            return ;
        }

        mNotificationPanel.expandWithoutQs();

        if (false) postStartTracing();
    }

    @Override
    public void animateExpandSettingsPanel(String subPanel) {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
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
        mStatusBarWindowManager.setPanelVisible(false);
        mStatusBarWindowManager.setForceStatusBarVisible(false);

        // Close any guts that might be visible
        mGutsManager.closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                true /* removeControls */, -1 /* x */, -1 /* y */, true /* resetMenu */);

        runPostCollapseRunnables();
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        showBouncerIfKeyguard();
        recomputeDisableFlags(mNotificationPanel.hideStatusBarIconsWhenExpanded() /* animate */);

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

    public FingerprintUnlockController getFingerprintUnlockController() {
        return mFingerprintUnlockController;
    }

    @Override // CommandQueue
    public void setWindowState(int window, int state) {
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
    public void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal&~mask) | (vis&mask);
        final int diff = newVal ^ oldVal;
        if (DEBUG) Log.d(TAG, String.format(
                "setSystemUiVisibility vis=%s mask=%s oldVal=%s newVal=%s diff=%s",
                Integer.toHexString(vis), Integer.toHexString(mask),
                Integer.toHexString(oldVal), Integer.toHexString(newVal),
                Integer.toHexString(diff)));
        boolean sbModeChanged = false;
        if (diff != 0) {
            mSystemUiVisibility = newVal;

            // update low profile
            if ((diff & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
                setAreThereNotifications();
            }

            // ready to unhide
            if ((vis & View.STATUS_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.STATUS_BAR_UNHIDE;
                mNoAnimationOnNextBarModeChange = true;
            }

            // update status bar mode
            final int sbMode = computeStatusBarMode(oldVal, newVal);

            sbModeChanged = sbMode != -1;
            if (sbModeChanged && sbMode != mStatusBarMode) {
                mStatusBarMode = sbMode;
                checkBarModes();
                touchAutoHide();
            }

            if ((vis & View.NAVIGATION_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.NAVIGATION_BAR_UNHIDE;
            }

            // send updated sysui visibility to window manager
            notifyUiVisibilityChanged(mSystemUiVisibility);
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

    void touchAutoHide() {
        // update transient bar autohide
        if (mStatusBarMode == MODE_SEMI_TRANSPARENT || (mNavigationBar != null
                && mNavigationBar.isSemiTransparent())) {
            scheduleAutohide();
        } else {
            cancelAutohide();
        }
    }

    protected int computeStatusBarMode(int oldVal, int newVal) {
        return computeBarMode(oldVal, newVal, View.STATUS_BAR_TRANSIENT,
                View.STATUS_BAR_TRANSLUCENT, View.STATUS_BAR_TRANSPARENT);
    }

    protected BarTransitions getStatusBarTransitions() {
        return mStatusBarView.getBarTransitions();
    }

    protected int computeBarMode(int oldVis, int newVis,
            int transientFlag, int translucentFlag, int transparentFlag) {
        final int oldMode = barMode(oldVis, transientFlag, translucentFlag, transparentFlag);
        final int newMode = barMode(newVis, transientFlag, translucentFlag, transparentFlag);
        if (oldMode == newMode) {
            return -1; // no mode change
        }
        return newMode;
    }

    private int barMode(int vis, int transientFlag, int translucentFlag, int transparentFlag) {
        int lightsOutTransparent = View.SYSTEM_UI_FLAG_LOW_PROFILE | transparentFlag;
        return (vis & transientFlag) != 0 ? MODE_SEMI_TRANSPARENT
                : (vis & translucentFlag) != 0 ? MODE_TRANSLUCENT
                : (vis & lightsOutTransparent) == lightsOutTransparent ? MODE_LIGHTS_OUT_TRANSPARENT
                : (vis & transparentFlag) != 0 ? MODE_TRANSPARENT
                : (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0 ? MODE_LIGHTS_OUT
                : MODE_OPAQUE;
    }

    void checkBarModes() {
        if (mDemoMode) return;
        if (mStatusBarView != null) checkBarMode(mStatusBarMode, mStatusBarWindowState,
                getStatusBarTransitions());
        if (mNavigationBar != null) mNavigationBar.checkNavBarModes();
        mNoAnimationOnNextBarModeChange = false;
    }

    // Called by NavigationBarFragment
    void setQsScrimEnabled(boolean scrimEnabled) {
        mNotificationPanel.setQsScrimEnabled(scrimEnabled);
    }

    void checkBarMode(int mode, int windowState, BarTransitions transitions) {
        final boolean anim = !mNoAnimationOnNextBarModeChange && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN;
        transitions.transitionTo(mode, anim);
    }

    private void finishBarAnimations() {
        if (mStatusBarView != null) {
            mStatusBarView.getBarTransitions().finishAnimations();
        }
        if (mNavigationBar != null) {
            mNavigationBar.finishBarAnimations();
        }
    }

    private final Runnable mCheckBarModes = this::checkBarModes;

    public void setInteracting(int barWindow, boolean interacting) {
        final boolean changing = ((mInteractingWindows & barWindow) != 0) != interacting;
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            suspendAutohide();
        } else {
            resumeSuspendedAutohide();
        }
        // manually dismiss the volume panel when interacting with the nav bar
        if (changing && interacting && barWindow == StatusBarManager.WINDOW_NAVIGATION_BAR) {
            touchAutoDim();
            dismissVolumeDialog();
        }
        checkBarModes();
    }

    private void dismissVolumeDialog() {
        if (mVolumeComponent != null) {
            mVolumeComponent.dismissNow();
        }
    }

    private void resumeSuspendedAutohide() {
        if (mAutohideSuspended) {
            scheduleAutohide();
            mHandler.postDelayed(mCheckBarModes, 500); // longer than home -> launcher
        }
    }

    private void suspendAutohide() {
        mHandler.removeCallbacks(mAutohide);
        mHandler.removeCallbacks(mCheckBarModes);
        mAutohideSuspended = (mSystemUiVisibility & STATUS_OR_NAV_TRANSIENT) != 0;
    }

    private void cancelAutohide() {
        mAutohideSuspended = false;
        mHandler.removeCallbacks(mAutohide);
    }

    private void scheduleAutohide() {
        cancelAutohide();
        mHandler.postDelayed(mAutohide, AUTOHIDE_TIMEOUT_MS);
    }

    public void touchAutoDim() {
        if (mNavigationBar != null) {
            mNavigationBar.getBarTransitions().setAutoDim(false);
        }
        mHandler.removeCallbacks(mAutoDim);
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
            mHandler.postDelayed(mAutoDim, AUTOHIDE_TIMEOUT_MS);
        }
    }

    void checkUserAutohide(MotionEvent event) {
        if ((mSystemUiVisibility & STATUS_OR_NAV_TRANSIENT) != 0  // a transient bar is revealed
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && !mRemoteInputManager.getController()
                        .isRemoteInputActive()) { // not due to typing in IME
            userAutohide();
        }
    }

    private void userAutohide() {
        cancelAutohide();
        mHandler.postDelayed(mAutohide, 350); // longer than app gesture -> flag clear
    }

    private boolean areLightsOn() {
        return 0 == (mSystemUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    public void setLightsOn(boolean on) {
        Log.v(TAG, "setLightsOn(" + on + ")");
        if (on) {
            setSystemUiVisibility(0, 0, 0, View.SYSTEM_UI_FLAG_LOW_PROFILE,
                    mLastFullscreenStackBounds, mLastDockedStackBounds);
        } else {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE, 0, 0,
                    View.SYSTEM_UI_FLAG_LOW_PROFILE, mLastFullscreenStackBounds,
                    mLastDockedStackBounds);
        }
    }

    private void notifyUiVisibilityChanged(int vis) {
        try {
            if (mLastDispatchedSystemUiVisibility != vis) {
                mWindowManagerService.statusBarVisibilityChanged(vis);
                mLastDispatchedSystemUiVisibility = vis;
            }
        } catch (RemoteException ex) {
        }
    }

    @Override
    public void topAppWindowChanged(boolean showMenu) {
        if (SPEW) {
            Log.d(TAG, (showMenu?"showing":"hiding") + " the MENU button");
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
        if (mStackScroller != null) {
            pw.print  ("      ");
            mStackScroller.dump(fd, pw, args);
        }
        pw.println("  Theme:");
        if (mOverlayManager == null) {
            pw.println("    overlay manager not initialized!");
        } else {
            pw.println("    dark overlay on: " + isUsingDarkTheme());
        }
        final boolean lightWpTheme = mContext.getThemeResId() == R.style.Theme_SystemUI_Light;
        pw.println("    light wallpaper theme: " + lightWpTheme);

        DozeLog.dump(pw);

        if (mFingerprintUnlockController != null) {
            mFingerprintUnlockController.dump(pw);
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

        if (KeyguardUpdateMonitor.getInstance(mContext) != null) {
            KeyguardUpdateMonitor.getInstance(mContext).dump(fd, pw, args);
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
        mStatusBarWindowManager = Dependency.get(StatusBarWindowManager.class);
        mRemoteInputManager.setUpWithPresenter(this, mEntryManager, this,
                new RemoteInputController.Delegate() {
                    public void setRemoteInputActive(NotificationData.Entry entry,
                            boolean remoteInputActive) {
                        mHeadsUpManager.setRemoteInputActive(entry, remoteInputActive);
                        entry.row.notifyHeightChanged(true /* needsAnimation */);
                        updateFooter();
                    }
                    public void lockScrollTo(NotificationData.Entry entry) {
                        mStackScroller.lockScrollTo(entry.row);
                    }
                    public void requestDisallowLongPressAndDismiss() {
                        mStackScroller.requestDisallowLongPress();
                        mStackScroller.requestDisallowDismiss();
                    }
                });
        mRemoteInputManager.getController().addCallback(mStatusBarWindowManager);
        mStatusBarWindowManager.add(mStatusBarWindow, getStatusBarHeight());
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
        if (onlyProvisioned && !isDeviceProvisioned()) return;

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
                result = ActivityManager.getService().startActivityAsUser(
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
                if (mExpandedVisible) {
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
                    updateMediaMetaData(true, true);
                }
            }
        }
    };

    public void resetUserExpandedStates() {
        ArrayList<Entry> activeNotifications = mEntryManager.getNotificationData()
                .getActiveNotifications();
        final int notificationCount = activeNotifications.size();
        for (int i = 0; i < notificationCount; i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
            if (entry.row != null) {
                entry.row.resetUserExpansion();
            }
        }
    }

    protected void dismissKeyguardThenExecute(OnDismissAction action, boolean afterKeyguardGone) {
        dismissKeyguardThenExecute(action, null /* cancelRunnable */, afterKeyguardGone);
    }

    private void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancelAction,
            boolean afterKeyguardGone) {
        if (mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP
                && mUnlockMethodCache.canSkipBouncer()
                && !mLeaveOpenOnKeyguardHide
                && isPulsing()) {
            // Reuse the fingerprint wake-and-unlock transition if we dismiss keyguard from a pulse.
            // TODO: Factor this transition out of FingerprintUnlockController.
            mFingerprintUnlockController.startWakeAndUnlock(
                    FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
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
    public void onUserSwitched(int newUserId) {
        // Begin old BaseStatusBar.userSwitched
        setHeadsUpUser(newUserId);
        // End old BaseStatusBar.userSwitched
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        animateCollapsePanels();
        updatePublicMode();
        mEntryManager.getNotificationData().filterAndSort();
        if (mReinflateNotificationsOnUserSwitched) {
            mEntryManager.updateNotificationsOnDensityOrFontScaleChanged();
            mReinflateNotificationsOnUserSwitched = false;
        }
        updateNotificationViews();
        mMediaManager.clearCurrentMediaNotification();
        setLockscreenUser(newUserId);
    }

    @Override
    public NotificationLockscreenUserManager getNotificationLockscreenUserManager() {
        return mLockscreenUserManager;
    }

    @Override
    public void onBindRow(Entry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row) {
        row.setAboveShelfChangedListener(mAboveShelfObserver);
        row.setSecureStateProvider(this::isKeyguardCurrentlySecure);
    }

    protected void setLockscreenUser(int newUserId) {
        mLockscreenWallpaper.setCurrentUser(newUserId);
        mScrimController.setCurrentUser(newUserId);
        updateMediaMetaData(true, false);
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
        if (mStatusBarWindowManager != null && mNaturalBarHeight != oldBarHeight) {
            mStatusBarWindowManager.setBarHeight(mNaturalBarHeight);
        }
        mMaxAllowedKeyguardNotifications = res.getInteger(
                R.integer.keyguard_max_notification_count);

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
                    !isPresenterFullyCollapsed() &&
                            (mState == StatusBarState.SHADE
                                    || mState == StatusBarState.SHADE_LOCKED);
            int notificationLoad = mEntryManager.getNotificationData().getActiveNotifications()
                    .size();
            if (pinnedHeadsUp && isPresenterFullyCollapsed()) {
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
            mLeaveOpenOnKeyguardHide = true;
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

    public void destroy() {
        // Begin old BaseStatusBar.destroy().
        mContext.unregisterReceiver(mBannerActionBroadcastReceiver);
        mLockscreenUserManager.destroy();
        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            // Ignore.
        }
        mEntryManager.destroy();
        // End old BaseStatusBar.destroy().
        if (mStatusBarWindow != null) {
            mWindowManager.removeViewImmediate(mStatusBarWindow);
            mStatusBarWindow = null;
        }
        if (mNavigationBarView != null) {
            mWindowManager.removeViewImmediate(mNavigationBarView);
            mNavigationBarView = null;
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.unregisterReceiver(mDemoReceiver);
        mAssistManager.destroy();

        if (mQSPanel != null && mQSPanel.getHost() != null) {
            mQSPanel.getHost().destroy();
        }
        Dependency.get(ActivityStarterDelegate.class).setActivityStarterImpl(null);
        mDeviceProvisionedController.removeCallback(mUserSetupObserver);
        Dependency.get(ConfigurationController.class).removeCallback(this);
        mAppOpsListener.destroy();
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
                if (mNavigationBar != null) {
                    mNavigationBar.getBarTransitions().transitionTo(barMode, animate);
                }
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

    /**
     * @return The {@link StatusBarState} the status bar is in.
     */
    public int getBarState() {
        return mState;
    }

    @Override
    public boolean isPresenterFullyCollapsed() {
        return mNotificationPanel.isFullyCollapsed();
    }

    public void showKeyguard() {
        mKeyguardRequested = true;
        mLeaveOpenOnKeyguardHide = false;
        mPendingRemoteInputView = null;
        updateIsKeyguard();
        mAssistManager.onLockscreenShown();
    }

    public boolean hideKeyguard() {
        mKeyguardRequested = false;
        return updateIsKeyguard();
    }

    /**
     * @return True if StatusBar state is FULLSCREEN_USER_SWITCHER.
     */
    public boolean isFullScreenUserSwitcherState() {
        return mState == StatusBarState.FULLSCREEN_USER_SWITCHER;
    }

    private boolean updateIsKeyguard() {
        boolean wakeAndUnlocking = mFingerprintUnlockController.getMode()
                == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardForDozing = mDozingRequested &&
                (!mDeviceInteractive || isGoingToSleep() && (isScreenFullyOff() || mIsKeyguard));
        boolean shouldBeKeyguard = (mKeyguardRequested || keyguardForDozing) && !wakeAndUnlocking;
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
        if (mLaunchTransitionFadingAway) {
            mNotificationPanel.animate().cancel();
            onLaunchTransitionFadingEnded();
        }
        mHandler.removeMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (mUserSwitcherController != null && mUserSwitcherController.useFullscreenUserSwitcher()) {
            setBarState(StatusBarState.FULLSCREEN_USER_SWITCHER);
        } else {
            setBarState(StatusBarState.KEYGUARD);
        }
        updateKeyguardState(false /* goingToFullShade */, false /* fromShadeLocked */);
        updatePanelExpansionForKeyguard();
        if (mDraggedDownRow != null) {
            mDraggedDownRow.setUserLocked(false);
            mDraggedDownRow.notifyHeightChanged(false  /* needsAnimation */);
            mDraggedDownRow = null;
        }
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mFingerprintUnlockController.getMode()
                != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK) {
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
        mLaunchTransitionFadingAway = false;
        updateMediaMetaData(true /* metaDataChanged */, true);
    }

    public boolean isCollapsing() {
        return mNotificationPanel.isCollapsing() || mActivityLaunchAnimator.isAnimationPending();
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
            mLaunchTransitionFadingAway = true;
            if (beforeFading != null) {
                beforeFading.run();
            }
            updateScrimController();
            updateMediaMetaData(false, true);
            mNotificationPanel.setAlpha(1);
            mStackScroller.setParentNotFullyVisible(true);
            mNotificationPanel.animate()
                    .alpha(0)
                    .setStartDelay(FADE_KEYGUARD_START_DELAY)
                    .setDuration(FADE_KEYGUARD_DURATION)
                    .withLayer()
                    .withEndAction(this::onLaunchTransitionFadingEnded);
            mCommandQueue.appTransitionStarting(SystemClock.uptimeMillis(),
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
        mNotificationPanel.notifyStartFading();
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
        mNotificationPanel.resetViews();
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
        boolean staying = mLeaveOpenOnKeyguardHide;
        setBarState(StatusBarState.SHADE);
        View viewToClick = null;
        if (mLeaveOpenOnKeyguardHide) {
            if (!mKeyguardRequested) {
                mLeaveOpenOnKeyguardHide = false;
            }
            long delay = calculateGoingToFullShadeDelay();
            mNotificationPanel.animateToFullShade(delay);
            if (mDraggedDownRow != null) {
                mDraggedDownRow.setUserLocked(false);
                mDraggedDownRow = null;
            }
            if (!mKeyguardRequested) {
                viewToClick = mPendingRemoteInputView;
                mPendingRemoteInputView = null;
            }

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            if (mNavigationBar != null) {
                mNavigationBar.disableAnimationsDuringHide(delay);
            }
        } else if (!mNotificationPanel.isCollapsing()) {
            instantCollapseNotificationPanel();
        }
        updateKeyguardState(staying, false /* fromShadeLocked */);

        if (viewToClick != null && viewToClick.isAttachedToWindow()) {
            viewToClick.callOnClick();
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
        Trace.endSection();
        return staying;
    }

    private void releaseGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
    }

    public long calculateGoingToFullShadeDelay() {
        return mKeyguardFadingAwayDelay + mKeyguardFadingAwayDuration;
    }

    /**
     * Notifies the status bar that Keyguard is going away very soon.
     */
    public void keyguardGoingAway() {

        // Treat Keyguard exit animation as an app transition to achieve nice transition for status
        // bar.
        mKeyguardMonitor.notifyKeyguardGoingAway(true);
        mCommandQueue.appTransitionPending(true);
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     *
     * @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration) {
        mKeyguardFadingAway = true;
        mKeyguardFadingAwayDelay = delay;
        mKeyguardFadingAwayDuration = fadeoutDuration;
        mCommandQueue.appTransitionStarting(startTime + fadeoutDuration
                        - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        recomputeDisableFlags(fadeoutDuration > 0 /* animate */);
        mCommandQueue.appTransitionStarting(
                    startTime - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mKeyguardMonitor.notifyKeyguardFadingAway(delay, fadeoutDuration);
    }

    public boolean isKeyguardFadingAway() {
        return mKeyguardFadingAway;
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    public void finishKeyguardFadingAway() {
        mKeyguardFadingAway = false;
        mKeyguardMonitor.notifyKeyguardDoneFading();
        mScrimController.setExpansionAffectsAlpha(true);
    }

    // TODO: Move this to NotificationLockscreenUserManager.
    private void updatePublicMode() {
        final boolean showingKeyguard = mStatusBarKeyguardViewManager.isShowing();
        final boolean devicePublic = showingKeyguard
                && mStatusBarKeyguardViewManager.isSecure(
                        mLockscreenUserManager.getCurrentUserId());

        // Look for public mode users. Users are considered public in either case of:
        //   - device keyguard is shown in secure mode;
        //   - profile is locked with a work challenge.
        SparseArray<UserInfo> currentProfiles = mLockscreenUserManager.getCurrentProfiles();
        for (int i = currentProfiles.size() - 1; i >= 0; i--) {
            final int userId = currentProfiles.valueAt(i).id;
            boolean isProfilePublic = devicePublic;
            if (!devicePublic && userId != mLockscreenUserManager.getCurrentUserId()) {
                // We can't rely on KeyguardManager#isDeviceLocked() for unified profile challenge
                // due to a race condition where this code could be called before
                // TrustManagerService updates its internal records, resulting in an incorrect
                // state being cached in mLockscreenPublicMode. (b/35951989)
                if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                        && mStatusBarKeyguardViewManager.isSecure(userId)) {
                    isProfilePublic = mKeyguardManager.isDeviceLocked(userId);
                }
            }
            mLockscreenUserManager.setLockscreenPublicMode(isProfilePublic, userId);
        }
    }

    protected void updateKeyguardState(boolean goingToFullShade, boolean fromShadeLocked) {
        Trace.beginSection("StatusBar#updateKeyguardState");
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardIndicationController.setVisible(true);
            mNotificationPanel.resetViews();
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(true, fromShadeLocked);
            }
            if (mStatusBarView != null) mStatusBarView.removePendingHideExpandedRunnables();
            if (mAmbientIndicationContainer != null) {
                mAmbientIndicationContainer.setVisibility(View.VISIBLE);
            }
        } else {
            mKeyguardIndicationController.setVisible(false);
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(false,
                        goingToFullShade ||
                        mState == StatusBarState.SHADE_LOCKED ||
                        fromShadeLocked);
            }
            if (mAmbientIndicationContainer != null) {
                mAmbientIndicationContainer.setVisibility(View.INVISIBLE);
            }
        }
        mNotificationPanel.setBarState(mState, mKeyguardFadingAway, goingToFullShade);
        updateTheme();
        updateDozingState();
        updatePublicMode();
        updateStackScrollerState(goingToFullShade, fromShadeLocked);
        mEntryManager.updateNotifications();
        checkBarModes();
        updateScrimController();
        updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
        mKeyguardMonitor.notifyKeyguardState(mStatusBarKeyguardViewManager.isShowing(),
                mUnlockMethodCache.isMethodSecure(),
                mStatusBarKeyguardViewManager.isOccluded());
        Trace.endSection();
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {
        final boolean inflated = mStackScroller != null;

        // The system wallpaper defines if QS should be light or dark.
        WallpaperColors systemColors = mColorExtractor
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        final boolean useDarkTheme = systemColors != null
                && (systemColors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        if (isUsingDarkTheme() != useDarkTheme) {
            mUiOffloadThread.submit(() -> {
                try {
                    mOverlayManager.setEnabled("com.android.systemui.theme.dark",
                            useDarkTheme, mLockscreenUserManager.getCurrentUserId());
                } catch (RemoteException e) {
                    Log.w(TAG, "Can't change theme", e);
                }
            });
        }

        // Lock wallpaper defines the color of the majority of the views, hence we'll use it
        // to set our default theme.
        final boolean lockDarkText = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK, true
                /* ignoreVisibility */).supportsDarkText();
        final int themeResId = lockDarkText ? R.style.Theme_SystemUI_Light : R.style.Theme_SystemUI;
        if (mContext.getThemeResId() != themeResId) {
            mContext.setTheme(themeResId);
            if (inflated) {
                onThemeChanged();
            }
        }

        if (inflated) {
            int which;
            if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
                which = WallpaperManager.FLAG_LOCK;
            } else {
                which = WallpaperManager.FLAG_SYSTEM;
            }
            final boolean useDarkText = mColorExtractor.getColors(which,
                    true /* ignoreVisibility */).supportsDarkText();
            mStackScroller.updateDecorViews(useDarkText);

            // Make sure we have the correct navbar/statusbar colors.
            mStatusBarWindowManager.setKeyguardDark(useDarkText);
        }
    }

    private void updateDozingState() {
        Trace.traceCounter(Trace.TRACE_TAG_APP, "dozing", mDozing ? 1 : 0);
        Trace.beginSection("StatusBar#updateDozingState");

        boolean sleepingFromKeyguard =
                mStatusBarKeyguardViewManager.isGoingToSleepVisibleNotOccluded();
        boolean animate = (!mDozing && mDozeServiceHost.shouldAnimateWakeup())
                || (mDozing && mDozeServiceHost.shouldAnimateScreenOff() && sleepingFromKeyguard);

        mStackScroller.setDark(mDozing, animate, mWakeUpTouchLocation);
        mDozeScrimController.setDozing(mDozing);
        mKeyguardIndicationController.setDozing(mDozing);
        mNotificationPanel.setDozing(mDozing, animate);
        updateQsExpansionEnabled();
        mViewHierarchyManager.updateRowStates();
        Trace.endSection();
    }

    public void updateStackScrollerState(boolean goingToFullShade, boolean fromShadeLocked) {
        if (mStackScroller == null) return;
        boolean onKeyguard = mState == StatusBarState.KEYGUARD;
        boolean publicMode = mLockscreenUserManager.isAnyProfilePublicMode();
        if (mHeadsUpAppearanceController != null) {
            mHeadsUpAppearanceController.setPublicMode(publicMode);
        }
        mStackScroller.setHideSensitive(publicMode, goingToFullShade);
        mStackScroller.setDimmed(onKeyguard, fromShadeLocked /* animate */);
        mStackScroller.setExpandingEnabled(!onKeyguard);
        ActivatableNotificationView activatedChild = mStackScroller.getActivatedChild();
        mStackScroller.setActivatedChild(null);
        if (activatedChild != null) {
            activatedChild.makeInactive(false /* animate */);
        }
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
                mNotificationPanel.animateCloseQs();
            }
            return true;
        }
        if (mState != StatusBarState.KEYGUARD && mState != StatusBarState.SHADE_LOCKED) {
            animateCollapsePanels();
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
            showBouncer(true /* animated */);
        }
    }

    protected void showBouncer(boolean animated) {
        mStatusBarKeyguardViewManager.showBouncer(animated);
    }

    private void instantExpandNotificationsPanel() {
        // Make our window larger and the panel expanded.
        makeExpandedVisible(true);
        mNotificationPanel.expand(false /* animate */);
        recomputeDisableFlags(false /* animate */);
    }

    private void instantCollapseNotificationPanel() {
        mNotificationPanel.instantCollapse();
        runPostCollapseRunnables();
    }

    @Override
    public void onActivated(ActivatableNotificationView view) {
        onActivated((View) view);
        mStackScroller.setActivatedChild(view);
    }

    public void onActivated(View view) {
        mLockscreenGestureLogger.write(
                MetricsEvent.ACTION_LS_NOTE,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        mKeyguardIndicationController.showTransientIndication(R.string.notification_tap_again);
        ActivatableNotificationView previousView = mStackScroller.getActivatedChild();
        if (previousView != null) {
            previousView.makeInactive(true /* animate */);
        }
    }

    /**
     * @param state The {@link StatusBarState} to set.
     */
    public void setBarState(int state) {
        // If we're visible and switched to SHADE_LOCKED (the user dragged
        // down on the lockscreen), clear notification LED, vibration,
        // ringing.
        // Other transitions are covered in handleVisibleToUserChanged().
        if (state != mState && mVisible && (state == StatusBarState.SHADE_LOCKED
                || (state == StatusBarState.SHADE && isGoingToNotificationShade()))) {
            clearNotificationEffects();
        }
        if (state == StatusBarState.KEYGUARD) {
            mRemoteInputManager.removeRemoteInputEntriesKeptUntilCollapsed();
            maybeEscalateHeadsUp();
        }
        mState = state;
        mGroupManager.setStatusBarState(state);
        mHeadsUpManager.setStatusBarState(state);
        mFalsingManager.setStatusBarState(state);
        mStatusBarWindowManager.setStatusBarState(state);
        mStackScroller.setStatusBarState(state);
        updateReportRejectedTouchVisibility();
        updateDozing();
        updateTheme();
        touchAutoDim();
        mNotificationShelf.setStatusBarState(state);
    }

    @Override
    public void onActivationReset(ActivatableNotificationView view) {
        if (view == mStackScroller.getActivatedChild()) {
            mStackScroller.setActivatedChild(null);
            onActivationReset((View)view);
        }
    }

    public void onActivationReset(View view) {
        mKeyguardIndicationController.hideTransientIndication();
    }

    public void onTrackingStarted() {
        runPostCollapseRunnables();
    }

    public void onClosingFinished() {
        runPostCollapseRunnables();
        if (!isPresenterFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            mStatusBarWindowManager.setStatusBarFocusable(true);
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
                showBouncer(false /* animated */);
            }
        }
    }

    @Override
    public int getMaxNotificationsWhileLocked(boolean recompute) {
        if (recompute) {
            mMaxKeyguardNotifications = Math.max(1,
                    mNotificationPanel.computeMaxKeyguardNotifications(
                            mMaxAllowedKeyguardNotifications));
            return mMaxKeyguardNotifications;
        }
        return mMaxKeyguardNotifications;
    }

    public int getMaxNotificationsWhileLocked() {
        return getMaxNotificationsWhileLocked(false /* recompute */);
    }

    // TODO: Figure out way to remove these.
    public NavigationBarView getNavigationBarView() {
        return (mNavigationBar != null ? (NavigationBarView) mNavigationBar.getView() : null);
    }

    public View getNavigationBarWindow() {
        return mNavigationBarView;
    }

    /**
     * TODO: Remove this method. Views should not be passed forward. Will cause theme issues.
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mNotificationPanel.getKeyguardBottomAreaView();
    }

    // ---------------------- DragDownHelper.OnDragDownListener ------------------------------------


    /* Only ever called as a consequence of a lockscreen expansion gesture. */
    @Override
    public boolean onDraggedDown(View startingChild, int dragLengthY) {
        if (mState == StatusBarState.KEYGUARD
                && hasActiveNotifications() && (!isDozing() || isPulsing())) {
            mLockscreenGestureLogger.write(
                    MetricsEvent.ACTION_LS_SHADE,
                    (int) (dragLengthY / mDisplayMetrics.density),
                    0 /* velocityDp - N/A */);

            // We have notifications, go to locked shade.
            goToLockedShade(startingChild);
            if (startingChild instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) startingChild;
                row.onExpandedByGesture(true /* drag down is always an open */);
            }
            return true;
        } else {
            // abort gesture.
            return false;
        }
    }

    @Override
    public void onDragDownReset() {
        mStackScroller.setDimmed(true /* dimmed */, true /* animated */);
        mStackScroller.resetScrollPosition();
        mStackScroller.resetCheckSnoozeLeavebehind();
    }

    @Override
    public void onCrossedThreshold(boolean above) {
        mStackScroller.setDimmed(!above /* dimmed */, true /* animate */);
    }

    @Override
    public void onTouchSlopExceeded() {
        mStackScroller.cancelLongPress();
        mStackScroller.checkSnoozeLeavebehind();
    }

    @Override
    public void setEmptyDragAmount(float amount) {
        mNotificationPanel.setEmptyDragAmount(amount);
    }

    @Override
    public boolean isFalsingCheckNeeded() {
        return mState == StatusBarState.KEYGUARD;
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
        if (expandView instanceof ExpandableNotificationRow) {
            row = (ExpandableNotificationRow) expandView;
            row.setUserExpanded(true /* userExpanded */, true /* allowChildExpansion */);
            // Indicate that the group expansion is changing at this time -- this way the group
            // and children backgrounds / divider animations will look correct.
            row.setGroupExpansionChanging(true);
            if (row.getStatusBarNotification() != null) {
                userId = row.getStatusBarNotification().getUserId();
            }
        }
        boolean fullShadeNeedsBouncer = !mLockscreenUserManager.
                userAllowsPrivateNotificationsInPublic(mLockscreenUserManager.getCurrentUserId())
                || !mLockscreenUserManager.shouldShowLockscreenNotifications()
                || mFalsingManager.shouldEnforceBouncer();
        if (mLockscreenUserManager.isLockscreenPublicMode(userId) && fullShadeNeedsBouncer) {
            mLeaveOpenOnKeyguardHide = true;
            showBouncerIfKeyguard();
            mDraggedDownRow = row;
            mPendingRemoteInputView = null;
        } else {
            mNotificationPanel.animateToFullShade(0 /* delay */);
            setBarState(StatusBarState.SHADE_LOCKED);
            updateKeyguardState(false /* goingToFullShade */, false /* fromShadeLocked */);
        }
    }

    public void onLockedNotificationImportanceChange(OnDismissAction dismissAction) {
        mLeaveOpenOnKeyguardHide = true;
        dismissKeyguardThenExecute(dismissAction, true /* afterKeyguardGone */);
    }

    @Override
    public void onLockedRemoteInput(ExpandableNotificationRow row, View clicked) {
        mLeaveOpenOnKeyguardHide = true;
        showBouncer(true /* animated */);
        mPendingRemoteInputView = clicked;
    }

    @Override
    public void onMakeExpandedVisibleForRemoteInput(ExpandableNotificationRow row,
            View clickedView) {
        if (isKeyguardShowing()) {
            onLockedRemoteInput(row, clickedView);
        } else {
            row.setUserExpanded(true);
            row.getPrivateLayout().setOnExpandedVisibleListener(clickedView::performClick);
        }
    }

    @Override
    public boolean shouldHandleRemoteInput(View view, PendingIntent pendingIntent) {
        // Skip remote input as doing so will expand the notification shade.
        return (mDisabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0;
    }

    @Override
    public boolean handleRemoteViewClick(View view, PendingIntent pendingIntent,
            Intent fillInIntent, NotificationRemoteInputManager.ClickHandler defaultHandler) {
        final boolean isActivity = pendingIntent.isActivity();
        if (isActivity) {
            final boolean afterKeyguardGone = PreviewInflater.wouldLaunchResolverActivity(
                    mContext, pendingIntent.getIntent(), mLockscreenUserManager.getCurrentUserId());
            dismissKeyguardThenExecute(() -> {
                try {
                    ActivityManager.getService().resumeAppSwitches();
                } catch (RemoteException e) {
                }

                boolean handled = defaultHandler.handleClick();

                // close the shade if it was open
                if (handled && !mNotificationPanel.isFullyCollapsed()) {
                    animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */);
                    visibilityChanged(false);
                    mAssistManager.hideAssist();

                    // Wait for activity start.
                    return true;
                } else {
                    return false;
                }

            }, afterKeyguardGone);
            return true;
        } else {
            return defaultHandler.handleClick();
        }
    }

    protected boolean startWorkChallengeIfNecessary(int userId, IntentSender intendSender,
            String notificationKey) {
        // Clear pending remote view, as we do not want to trigger pending remote input view when
        // it's called by other code
        mPendingWorkRemoteInputView = null;
        // Begin old BaseStatusBar.startWorkChallengeIfNecessary.
        final Intent newIntent = mKeyguardManager.createConfirmDeviceCredentialIntent(null,
                null, userId);
        if (newIntent == null) {
            return false;
        }
        final Intent callBackIntent = new Intent(NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION);
        callBackIntent.putExtra(Intent.EXTRA_INTENT, intendSender);
        callBackIntent.putExtra(Intent.EXTRA_INDEX, notificationKey);
        callBackIntent.setPackage(mContext.getPackageName());

        PendingIntent callBackPendingIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                callBackIntent,
                PendingIntent.FLAG_CANCEL_CURRENT |
                        PendingIntent.FLAG_ONE_SHOT |
                        PendingIntent.FLAG_IMMUTABLE);
        newIntent.putExtra(
                Intent.EXTRA_INTENT,
                callBackPendingIntent.getIntentSender());
        try {
            ActivityManager.getService().startConfirmDeviceCredentialIntent(newIntent,
                    null /*options*/);
        } catch (RemoteException ex) {
            // ignore
        }
        return true;
        // End old BaseStatusBar.startWorkChallengeIfNecessary.
    }

    @Override
    public void onLockedWorkRemoteInput(int userId, ExpandableNotificationRow row,
            View clicked) {
        // Collapse notification and show work challenge
        animateCollapsePanels();
        startWorkChallengeIfNecessary(userId, null, null);
        // Add pending remote input view after starting work challenge, as starting work challenge
        // will clear all previous pending review view
        mPendingWorkRemoteInputView = clicked;
    }

    @Override
    public void onWorkChallengeChanged() {
        updatePublicMode();
        mEntryManager.updateNotifications();
        if (mPendingWorkRemoteInputView != null
                && !mLockscreenUserManager.isAnyProfilePublicMode()) {
            // Expand notification panel and the notification row, then click on remote input view
            final Runnable clickPendingViewRunnable = () -> {
                final View pendingWorkRemoteInputView = mPendingWorkRemoteInputView;
                if (pendingWorkRemoteInputView == null) {
                    return;
                }

                // Climb up the hierarchy until we get to the container for this row.
                ViewParent p = pendingWorkRemoteInputView.getParent();
                while (!(p instanceof ExpandableNotificationRow)) {
                    if (p == null) {
                        return;
                    }
                    p = p.getParent();
                }

                final ExpandableNotificationRow row = (ExpandableNotificationRow) p;
                ViewParent viewParent = row.getParent();
                if (viewParent instanceof NotificationStackScrollLayout) {
                    final NotificationStackScrollLayout scrollLayout =
                            (NotificationStackScrollLayout) viewParent;
                    row.makeActionsVisibile();
                    row.post(() -> {
                        final Runnable finishScrollingCallback = () -> {
                            mPendingWorkRemoteInputView.callOnClick();
                            mPendingWorkRemoteInputView = null;
                            scrollLayout.setFinishScrollingCallback(null);
                        };
                        if (scrollLayout.scrollTo(row)) {
                            // It scrolls! So call it when it's finished.
                            scrollLayout.setFinishScrollingCallback(finishScrollingCallback);
                        } else {
                            // It does not scroll, so call it now!
                            finishScrollingCallback.run();
                        }
                    });
                }
            };
            mNotificationPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (mNotificationPanel.mStatusBar.getStatusBarWindow()
                                    .getHeight() != mNotificationPanel.mStatusBar
                                            .getStatusBarHeight()) {
                                mNotificationPanel.getViewTreeObserver()
                                        .removeOnGlobalLayoutListener(this);
                                mNotificationPanel.post(clickPendingViewRunnable);
                            }
                        }
                    });
            instantExpandNotificationsPanel();
        }
    }

    @Override
    public void onExpandClicked(Entry clickedEntry, boolean nowExpanded) {
        mHeadsUpManager.setExpanded(clickedEntry, nowExpanded);
        if (mState == StatusBarState.KEYGUARD && nowExpanded) {
            goToLockedShade(clickedEntry.row);
        }
    }

    /**
     * Goes back to the keyguard after hanging around in {@link StatusBarState#SHADE_LOCKED}.
     */
    public void goToKeyguard() {
        if (mState == StatusBarState.SHADE_LOCKED) {
            mStackScroller.onGoToKeyguard();
            setBarState(StatusBarState.KEYGUARD);
            updateKeyguardState(false /* goingToFullShade */, true /* fromShadeLocked*/);
        }
    }

    public long getKeyguardFadingAwayDelay() {
        return mKeyguardFadingAwayDelay;
    }

    public long getKeyguardFadingAwayDuration() {
        return mKeyguardFadingAwayDuration;
    }

    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        if (mStatusBarView != null) mStatusBarView.setBouncerShowing(bouncerShowing);
        updateHideIconsForBouncer(true /* animate */);
        recomputeDisableFlags(true /* animate */);
        updateScrimController();
    }

    public void cancelCurrentTouch() {
        if (mNotificationPanel.isTracking()) {
            mStatusBarWindow.cancelCurrentTouch();
            if (mState == StatusBarState.SHADE) {
                animateCollapsePanels();
            }
        }
    }

    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mNotificationPanel.onAffordanceLaunchEnded();
            releaseGestureWakeLock();
            mLaunchCameraOnScreenTurningOn = false;
            mDeviceInteractive = false;
            mWakeUpComingFromTouch = false;
            mWakeUpTouchLocation = null;
            mStackScroller.setAnimationsEnabled(false);
            mVisualStabilityManager.setScreenOn(false);
            updateVisibleToUser();

            // We need to disable touch events because these might
            // collapse the panel after we expanded it, and thus we would end up with a blank
            // Keyguard.
            mNotificationPanel.setTouchDisabled(true);
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
            mStackScroller.setAnimationsEnabled(true);
            mVisualStabilityManager.setScreenOn(true);
            mNotificationPanel.setTouchDisabled(false);
            mDozeServiceHost.stopDozing();
            updateVisibleToUser();
            updateIsKeyguard();
            updateScrimController();
        }
    };

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
            // If we pulse in from AOD, we turn the screen off first. However, updatingIsKeyguard
            // in that case destroys the HeadsUpManager state, so don't do it in that case.
            if (!isPulsing()) {
                updateIsKeyguard();
            }
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
    public void wakeUpIfDozing(long time, View where) {
        if (mDozing) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            pm.wakeUp(time, "com.android.systemui:NODOZE");
            mWakeUpComingFromTouch = true;
            where.getLocationInWindow(mTmpInt2);
            mWakeUpTouchLocation = new PointF(mTmpInt2[0] + where.getWidth() / 2,
                    mTmpInt2[1] + where.getHeight() / 2);
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
            mFalsingManager.onScreenOnFromTouch();
        }
    }

    @Override
    public boolean isDeviceLocked(int userId) {
        return mKeyguardManager.isDeviceLocked(userId);
    }

    @Override
    public void appTransitionCancelled() {
        EventBus.getDefault().send(new AppTransitionFinishedEvent());
    }

    @Override
    public void appTransitionFinished() {
        EventBus.getDefault().send(new AppTransitionFinishedEvent());
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
            pm.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:CAMERA_GESTURE");
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

    public void notifyFpAuthModeChanged() {
        updateDozing();
        updateScrimController();
    }

    private void updateDozing() {
        Trace.beginSection("StatusBar#updateDozing");
        // When in wake-and-unlock while pulsing, keep dozing state until fully unlocked.
        boolean dozing = mDozingRequested && mState == StatusBarState.KEYGUARD
                || mFingerprintUnlockController.getMode()
                        == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;
        final boolean alwaysOn = DozeParameters.getInstance(mContext).getAlwaysOn();
        // When in wake-and-unlock we may not have received a change to mState
        // but we still should not be dozing, manually set to false.
        if (mFingerprintUnlockController.getMode() ==
                FingerprintUnlockController.MODE_WAKE_AND_UNLOCK) {
            dozing = false;
        }
        mDozing = dozing;
        mKeyguardViewMediator.setAodShowing(mDozing && alwaysOn);
        mStatusBarWindowManager.setDozing(mDozing);
        mStatusBarKeyguardViewManager.setDozing(mDozing);
        if (mAmbientIndicationContainer instanceof DozeReceiver) {
            ((DozeReceiver) mAmbientIndicationContainer).setDozing(mDozing);
        }
        mEntryManager.updateNotifications();
        updateDozingState();
        updateReportRejectedTouchVisibility();
        Trace.endSection();
    }

    @VisibleForTesting
    void updateScrimController() {
        Trace.beginSection("StatusBar#updateScrimController");

        // We don't want to end up in KEYGUARD state when we're unlocking with
        // fingerprint from doze. We should cross fade directly from black.
        final boolean wakeAndUnlocking = mFingerprintUnlockController.getMode()
                == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;

        // Do not animate the scrim expansion when triggered by the fingerprint sensor.
        mScrimController.setExpansionAffectsAlpha(!mFingerprintUnlockController.isWakeAndUnlock());

        if (mBouncerShowing) {
            // Bouncer needs the front scrim when it's on top of an activity,
            // tapping on a notification, editing QS or being dismissed by
            // FLAG_DISMISS_KEYGUARD_ACTIVITY.
            ScrimState state = mIsOccluded || mNotificationPanel.needsScrimming()
                    || mStatusBarKeyguardViewManager.willDismissWithAction()
                    || mStatusBarKeyguardViewManager.isFullscreenBouncer() ?
                    ScrimState.BOUNCER_SCRIMMED : ScrimState.BOUNCER;
            mScrimController.transitionTo(state);
        } else if (mLaunchCameraOnScreenTurningOn || isInLaunchTransition()) {
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        } else if (mBrightnessMirrorVisible) {
            mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        } else if (isPulsing()) {
            // Handled in DozeScrimController#setPulsing
        } else if (mDozing) {
            mScrimController.transitionTo(ScrimState.AOD);
        } else if (mIsKeyguard && !wakeAndUnlocking) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
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

    private final class DozeServiceHost implements DozeHost {
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

        public void fireNotificationHeadsUp() {
            for (Callback callback : mCallbacks) {
                callback.onNotificationHeadsUp();
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
            if (reason == DozeLog.PULSE_REASON_SENSOR_LONG_PRESS) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:NODOZE");
                startAssist(new Bundle());
                return;
            }

            mDozeScrimController.pulse(new PulseCallback() {
                @Override
                public void onPulseStarted() {
                    callback.onPulseStarted();
                    if (mHeadsUpManager.hasHeadsUpNotifications()) {
                        // Only pulse the stack scroller if there's actually something to show.
                        // Otherwise just show the always-on screen.
                        setPulsing(true);
                    }
                }

                @Override
                public void onPulseFinished() {
                    callback.onPulseFinished();
                    setPulsing(false);
                }

                private void setPulsing(boolean pulsing) {
                    mNotificationPanel.setPulsing(pulsing);
                    mVisualStabilityManager.setPulsing(pulsing);
                    mIgnoreTouchWhilePulsing = false;
                }
            }, reason);
        }

        @Override
        public void stopDozing() {
            if (mDozingRequested) {
                mDozingRequested = false;
                DozeLog.traceDozing(mContext, mDozing);
                mWakefulnessLifecycle.dispatchStartedWakingUp();
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
        }

        @Override
        public boolean isPowerSaveActive() {
            return mBatteryController.isAodPowerSave();
        }

        @Override
        public boolean isPulsingBlocked() {
            return mFingerprintUnlockController.getMode()
                    == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;
        }

        @Override
        public boolean isProvisioned() {
            return mDeviceProvisionedController.isDeviceProvisioned()
                    && mDeviceProvisionedController.isCurrentUserSetup();
        }

        @Override
        public boolean isBlockingDoze() {
            if (mFingerprintUnlockController.hasPendingAuthentication()) {
                Log.i(TAG, "Blocking AOD because fingerprint has authenticated");
                return true;
            }
            return false;
        }

        @Override
        public void startPendingIntentDismissingKeyguard(PendingIntent intent) {
            StatusBar.this.startPendingIntentDismissingKeyguard(intent);
        }

        @Override
        public void extendPulse() {
            mDozeScrimController.extendPulse();
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
        public void onDoubleTap(float screenX, float screenY) {
            if (screenX > 0 && screenY > 0 && mAmbientIndicationContainer != null
                && mAmbientIndicationContainer.getVisibility() == View.VISIBLE) {
                mAmbientIndicationContainer.getLocationOnScreen(mTmpInt2);
                float viewX = screenX - mTmpInt2[0];
                float viewY = screenY - mTmpInt2[1];
                if (0 <= viewX && viewX <= mAmbientIndicationContainer.getWidth()
                        && 0 <= viewY && viewY <= mAmbientIndicationContainer.getHeight()) {
                    dispatchDoubleTap(viewX, viewY);
                }
            }
        }

        @Override
        public void setDozeScreenBrightness(int value) {
            mStatusBarWindowManager.setDozeScreenBrightness(value);
        }

        @Override
        public void setAodDimmingScrim(float scrimOpacity) {
            mScrimController.setAodFrontScrimAlpha(scrimOpacity);
        }

        public void dispatchDoubleTap(float viewX, float viewY) {
            dispatchTap(mAmbientIndicationContainer, viewX, viewY);
            dispatchTap(mAmbientIndicationContainer, viewX, viewY);
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
    protected NotificationStackScrollLayout mStackScroller;

    protected NotificationGroupManager mGroupManager;


    // for heads up notifications
    protected HeadsUpManagerPhone mHeadsUpManager;

    private AboveShelfObserver mAboveShelfObserver;

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
    private LockPatternUtils mLockPatternUtils;
    private DeviceProvisionedController mDeviceProvisionedController
            = Dependency.get(DeviceProvisionedController.class);

    // UI-specific methods

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;

    protected Display mDisplay;

    protected RecentsComponent mRecents;

    protected NotificationShelf mNotificationShelf;
    protected FooterView mFooterView;
    protected EmptyShadeView mEmptyShadeView;

    protected AssistManager mAssistManager;

    protected boolean mVrMode;

    public boolean isDeviceInteractive() {
        return mDeviceInteractive;
    }

    @Override  // NotificationData.Environment
    public boolean isDeviceProvisioned() {
        return mDeviceProvisionedController.isDeviceProvisioned();
    }

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mVrMode = enabled;
        }
    };

    public boolean isDeviceInVrMode() {
        return mVrMode;
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
    public void onNotificationClicked(StatusBarNotification sbn, ExpandableNotificationRow row) {
        RemoteInputController controller = mRemoteInputManager.getController();
        if (controller.isRemoteInputActive(row.getEntry())
                && !TextUtils.isEmpty(row.getActiveRemoteInputText())) {
            // We have an active remote input typed and the user clicked on the notification.
            // this was probably unintentional, so we're closing the edit text instead.
            controller.closeRemoteInputs();
            return;
        }
        Notification notification = sbn.getNotification();
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        final String notificationKey = sbn.getKey();

        final boolean afterKeyguardGone = intent.isActivity()
                && PreviewInflater.wouldLaunchResolverActivity(mContext, intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        dismissKeyguardThenExecute(() -> {
            // TODO: Some of this code may be able to move to NotificationEntryManager.
            if (mHeadsUpManager != null && mHeadsUpManager.isHeadsUp(notificationKey)) {
                // Release the HUN notification to the shade.

                if (isPresenterFullyCollapsed()) {
                    HeadsUpUtil.setIsClickedHeadsUpNotification(row, true);
                }
                //
                // In most cases, when FLAG_AUTO_CANCEL is set, the notification will
                // become canceled shortly by NoMan, but we can't assume that.
                mHeadsUpManager.releaseImmediately(notificationKey);
            }
            StatusBarNotification parentToCancel = null;
            if (shouldAutoCancel(sbn) && mGroupManager.isOnlyChildInGroup(sbn)) {
                StatusBarNotification summarySbn =
                        mGroupManager.getLogicalGroupSummary(sbn).getStatusBarNotification();
                if (shouldAutoCancel(summarySbn)) {
                    parentToCancel = summarySbn;
                }
            }
            final StatusBarNotification parentToCancelFinal = parentToCancel;
            final Runnable runnable = () -> {
                try {
                    // The intent we are sending is for the application, which
                    // won't have permission to immediately start an activity after
                    // the user switches to home.  We know it is safe to do at this
                    // point, so make sure new activity switches are now allowed.
                    ActivityManager.getService().resumeAppSwitches();
                } catch (RemoteException e) {
                }
                int launchResult = ActivityManager.START_CANCELED;
                if (intent != null) {
                    // If we are launching a work activity and require to launch
                    // separate work challenge, we defer the activity action and cancel
                    // notification until work challenge is unlocked.
                    if (intent.isActivity()) {
                        final int userId = intent.getCreatorUserHandle().getIdentifier();
                        if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                                && mKeyguardManager.isDeviceLocked(userId)) {
                            // TODO(b/28935539): should allow certain activities to
                            // bypass work challenge
                            if (startWorkChallengeIfNecessary(userId, intent.getIntentSender(),
                                    notificationKey)) {
                                // Show work challenge, do not run PendingIntent and
                                // remove notification
                                collapseOnMainThread();
                                return;
                            }
                        }
                    }
                    Intent fillInIntent = null;
                    Entry entry = row.getEntry();
                    CharSequence remoteInputText = null;
                    if (!TextUtils.isEmpty(entry.remoteInputText)) {
                        remoteInputText = entry.remoteInputText;
                    }
                    if (!TextUtils.isEmpty(remoteInputText)
                            && !controller.isSpinning(entry.key)) {
                        fillInIntent = new Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT,
                                remoteInputText.toString());
                    }
                    RemoteAnimationAdapter adapter = mActivityLaunchAnimator.getLaunchAnimation(
                            row);
                    try {
                        if (adapter != null) {
                            ActivityManager.getService()
                                    .registerRemoteAnimationForNextActivityStart(
                                            intent.getCreatorPackage(), adapter);
                        }
                        launchResult = intent.sendAndReturnResult(mContext, 0, fillInIntent, null,
                                null, null, getActivityOptions(adapter));
                        mActivityLaunchAnimator.setLaunchResult(launchResult);
                    } catch (RemoteException | PendingIntent.CanceledException e) {
                        // the stack trace isn't very helpful here.
                        // Just log the exception message.
                        Log.w(TAG, "Sending contentIntent failed: " + e);

                        // TODO: Dismiss Keyguard.
                    }
                    if (intent.isActivity()) {
                        mAssistManager.hideAssist();
                    }
                }
                if (shouldCollapse()) {
                    collapseOnMainThread();
                }

                final int count =
                        mEntryManager.getNotificationData().getActiveNotifications().size();
                final int rank = mEntryManager.getNotificationData().getRank(notificationKey);
                final NotificationVisibility nv = NotificationVisibility.obtain(notificationKey,
                        rank, count, true);
                try {
                    mBarService.onNotificationClick(notificationKey, nv);
                } catch (RemoteException ex) {
                    // system process is dead if we're here.
                }
                if (parentToCancelFinal != null) {
                    removeNotification(parentToCancelFinal);
                }
                if (shouldAutoCancel(sbn)) {
                    // Automatically remove all notifications that we may have kept around longer
                    removeNotification(sbn);
                }
            };

            if (mStatusBarKeyguardViewManager.isShowing()
                    && mStatusBarKeyguardViewManager.isOccluded()) {
                mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
            } else {
                new Thread(runnable).start();
            }

            return !mNotificationPanel.isFullyCollapsed();
        }, afterKeyguardGone);
    }

    private void collapseOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            collapsePanel();
        } else {
            mStackScroller.post(this::collapsePanel);
        }
    }

    private boolean shouldCollapse() {
        return mState != StatusBarState.SHADE || !mActivityLaunchAnimator.isAnimationPending();
    }

    public void collapsePanel(boolean animate) {
        if (animate) {
            collapsePanel();
        } else if (!isPresenterFullyCollapsed()) {
            instantCollapseNotificationPanel();
            visibilityChanged(false);
        } else {
            runPostCollapseRunnables();
        }
    }

    private boolean collapsePanel() {
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

    private void removeNotification(StatusBarNotification notification) {
        // We have to post it to the UI thread for synchronization
        mHandler.post(() -> {
            Runnable removeRunnable =
                    () -> mEntryManager.performRemoveNotification(notification);
            if (isCollapsing()) {
                // To avoid lags we're only performing the remove
                // after the shade was collapsed
                addPostCollapseAction(removeRunnable);
            } else {
                removeRunnable.run();
            }
        });
    }

    protected NotificationListener mNotificationListener;

    @Override  // NotificationData.Environment
    public boolean isNotificationForCurrentProfiles(StatusBarNotification n) {
        final int notificationUserId = n.getUserId();
        if (DEBUG && MULTIUSER_DEBUG) {
            Log.v(TAG, String.format("%s: current userid: %d, notification userid: %d", n,
                    mLockscreenUserManager.getCurrentUserId(), notificationUserId));
        }
        return mLockscreenUserManager.isCurrentProfile(notificationUserId);
    }

    @Override
    public NotificationGroupManager getGroupManager() {
        return mGroupManager;
    }

    @Override
    public void startNotificationGutsIntent(final Intent intent, final int appUid,
            ExpandableNotificationRow row) {
        dismissKeyguardThenExecute(() -> {
            AsyncTask.execute(() -> {
                int launchResult = TaskStackBuilder.create(mContext)
                        .addNextIntentWithParentStack(intent)
                        .startActivities(getActivityOptions(
                                mActivityLaunchAnimator.getLaunchAnimation(row)),
                                new UserHandle(UserHandle.getUserId(appUid)));
                mActivityLaunchAnimator.setLaunchResult(launchResult);
                if (shouldCollapse()) {
                    // Putting it back on the main thread, since we're touching views
                    mStatusBarWindow.post(() -> animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */));
                }
            });
            return true;
        }, false /* afterKeyguardGone */);
    }

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
        SystemServicesProxy.getInstance(mContext).awakenDreamsAsync();
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
            recomputeDisableFlags(true);
        }
        updateHideIconsForBouncer(true /* animate */);
    }

    protected void toggleKeyboardShortcuts(int deviceId) {
        KeyboardShortcuts.toggle(mContext, deviceId);
    }

    protected void dismissKeyboardShortcuts() {
        KeyboardShortcuts.dismiss();
    }

    @Override  // NotificationData.Environment
    public boolean shouldHideNotifications(int userId) {
        return mLockscreenUserManager.shouldHideNotifications(userId);
    }

    @Override // NotificationDate.Environment
    public boolean shouldHideNotifications(String key) {
        return mLockscreenUserManager.shouldHideNotifications(key);
    }

    /**
     * Returns true if we're on a secure lockscreen.
     */
    @Override  // NotificationData.Environment
    public boolean isSecurelyLocked(int userId) {
        return mLockscreenUserManager.isLockscreenPublicMode(userId);
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
            int maxBefore = getMaxNotificationsWhileLocked(false /* recompute */);
            int maxNotifications = getMaxNotificationsWhileLocked(true /* recompute */);
            if (maxBefore != maxNotifications) {
                mViewHierarchyManager.updateRowStates();
            }
        }
    }

    public void startPendingIntentDismissingKeyguard(final PendingIntent intent) {
        if (!isDeviceProvisioned()) return;

        final boolean afterKeyguardGone = intent.isActivity()
                && PreviewInflater.wouldLaunchResolverActivity(mContext, intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
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
            }).start();

            return collapsePanel();
        }, afterKeyguardGone);
    }

    private boolean shouldAutoCancel(StatusBarNotification sbn) {
        int flags = sbn.getNotification().flags;
        if ((flags & Notification.FLAG_AUTO_CANCEL) != Notification.FLAG_AUTO_CANCEL) {
            return false;
        }
        if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return false;
        }
        return true;
    }

    protected Bundle getActivityOptions(@Nullable RemoteAnimationAdapter animationAdapter) {
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

    /**
     * Updates expanded, dimmed and locked states of notification rows.
     */
    @Override
    public void onUpdateRowStates() {
        // The following views will be moved to the end of mStackScroller. This counter represents
        // the offset from the last child. Initialized to 1 for the very last position. It is post-
        // incremented in the following "changeViewPosition" calls so that its value is correct for
        // subsequent calls.
        int offsetFromEnd = 1;
        if (mFooterView != null) {
            mStackScroller.changeViewPosition(mFooterView,
                    mStackScroller.getChildCount() - offsetFromEnd++);
        }

        mStackScroller.changeViewPosition(mEmptyShadeView,
                mStackScroller.getChildCount() - offsetFromEnd++);

        // No post-increment for this call because it is the last one. Make sure to add one if
        // another "changeViewPosition" call is ever added.
        mStackScroller.changeViewPosition(mNotificationShelf,
                mStackScroller.getChildCount() - offsetFromEnd);

        // Scrim opacity varies based on notification count
        mScrimController.setNotificationCount(mStackScroller.getNotGoneChildCount());
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

    private final Runnable mAutoDim = () -> {
        if (mNavigationBar != null) {
            mNavigationBar.getBarTransitions().setAutoDim(true);
        }
    };

    public NotificationGutsManager getGutsManager() {
        return mGutsManager;
    }

    @Override
    public boolean isPresenterLocked() {
        return mState == StatusBarState.KEYGUARD;
    }

    @Override
    public Handler getHandler() {
        return mHandler;
    }

    private final NotificationInfo.CheckSaveListener mCheckSaveListener =
            (Runnable saveImportance, StatusBarNotification sbn) -> {
                // If the user has security enabled, show challenge if the setting is changed.
                if (mLockscreenUserManager.isLockscreenPublicMode(sbn.getUser().getIdentifier())
                        && (mState == StatusBarState.KEYGUARD ||
                                mState == StatusBarState.SHADE_LOCKED)) {
                    onLockedNotificationImportanceChange(() -> {
                        saveImportance.run();
                        return true;
                    });
                } else {
                    saveImportance.run();
                }
            };
}
