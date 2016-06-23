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


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.AutoReinflateContainer.InflateListener;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.DemoMode;
import com.android.systemui.EventLogConstants;
import com.android.systemui.EventLogTags;
import com.android.systemui.Interpolators;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.qs.QSContainer;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.AppTransitionFinishedEvent;
import com.android.systemui.recents.events.activity.UndockingTaskEvent;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DismissView;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.NotificationOverflowContainer;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.UnlockMethodCache.OnUnlockMethodChangedListener;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.HotspotControllerImpl;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.LocationControllerImpl;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.PreviewInflater;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.SecurityControllerImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout.OnChildLocationsChangedListener;
import com.android.systemui.statusbar.stack.StackStateAnimator;
import com.android.systemui.statusbar.stack.StackViewState;
import com.android.systemui.volume.VolumeComponent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.windowStateToString;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_WARNING;

public class PhoneStatusBar extends BaseStatusBar implements DemoMode,
        DragDownHelper.DragDownCallback, ActivityStarter, OnUnlockMethodChangedListener,
        HeadsUpManager.OnHeadsUpChangedListener {
    static final String TAG = "PhoneStatusBar";
    public static final boolean DEBUG = BaseStatusBar.DEBUG;
    public static final boolean SPEW = false;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info
    public static final boolean DEBUG_GESTURES = false;
    public static final boolean DEBUG_MEDIA = false;
    public static final boolean DEBUG_MEDIA_FAKE_ARTWORK = false;

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

    private static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    private static final int STATUS_OR_NAV_TRANSIENT =
            View.STATUS_BAR_TRANSIENT | View.NAVIGATION_BAR_TRANSIENT;
    private static final long AUTOHIDE_TIMEOUT_MS = 3000;

    /** The minimum delay in ms between reports of notification visibility. */
    private static final int VISIBILITY_REPORT_MIN_DELAY_MS = 500;

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

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;

    /** If true, the system is in the half-boot-to-decryption-screen state.
     * Prudently disable QS and notifications.  */
    private static final boolean ONLY_CORE_APPS;

    /** If true, the lockscreen will show a distinct wallpaper */
    private static final boolean ENABLE_LOCKSCREEN_WALLPAPER = true;

    /* If true, the device supports freeform window management.
     * This affects the status bar UI. */
    private static final boolean FREEFORM_WINDOW_MANAGEMENT;

    /**
     * How long to wait before auto-dismissing a notification that was kept for remote input, and
     * has now sent a remote input. We auto-dismiss, because the app may not see a reason to cancel
     * these given that they technically don't exist anymore. We wait a bit in case the app issues
     * an update.
     */
    private static final int REMOTE_INPUT_KEPT_ENTRY_AUTO_CANCEL_DELAY = 200;

    static {
        boolean onlyCoreApps;
        boolean freeformWindowManagement;
        try {
            IPackageManager packageManager =
                    IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            onlyCoreApps = packageManager.isOnlyCoreApps();
            freeformWindowManagement = packageManager.hasSystemFeature(
                    PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT, 0);
        } catch (RemoteException e) {
            onlyCoreApps = false;
            freeformWindowManagement = false;
        }
        ONLY_CORE_APPS = onlyCoreApps;
        FREEFORM_WINDOW_MANAGEMENT = freeformWindowManagement;
    }

    PhoneStatusBarPolicy mIconPolicy;

    // These are no longer handled by the policy, because we need custom strategies for them
    BluetoothControllerImpl mBluetoothController;
    SecurityControllerImpl mSecurityController;
    protected BatteryController mBatteryController;
    LocationControllerImpl mLocationController;
    NetworkControllerImpl mNetworkController;
    HotspotControllerImpl mHotspotController;
    RotationLockControllerImpl mRotationLockController;
    UserInfoController mUserInfoController;
    protected ZenModeController mZenModeController;
    CastControllerImpl mCastController;
    VolumeComponent mVolumeComponent;
    KeyguardUserSwitcher mKeyguardUserSwitcher;
    FlashlightController mFlashlightController;
    protected UserSwitcherController mUserSwitcherController;
    NextAlarmController mNextAlarmController;
    protected KeyguardMonitor mKeyguardMonitor;
    BrightnessMirrorController mBrightnessMirrorController;
    AccessibilityController mAccessibilityController;
    FingerprintUnlockController mFingerprintUnlockController;
    LightStatusBarController mLightStatusBarController;
    protected LockscreenWallpaper mLockscreenWallpaper;

    int mNaturalBarHeight = -1;

    Display mDisplay;
    Point mCurrentDisplaySize = new Point();

    protected StatusBarWindowView mStatusBarWindow;
    protected PhoneStatusBarView mStatusBarView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    protected StatusBarWindowManager mStatusBarWindowManager;
    private UnlockMethodCache mUnlockMethodCache;
    private DozeServiceHost mDozeServiceHost;
    private boolean mWakeUpComingFromTouch;
    private PointF mWakeUpTouchLocation;
    private boolean mScreenTurningOn;

    int mPixelFormat;
    Object mQueueLock = new Object();

    protected StatusBarIconController mIconController;

    // expanded notifications
    protected NotificationPanelView mNotificationPanel; // the sliding/resizing panel within the notification window
    View mExpandedContents;
    TextView mNotificationPanelDebugText;

    // settings
    private QSPanel mQSPanel;

    // top bar
    BaseStatusBarHeader mHeader;
    protected KeyguardStatusBarView mKeyguardStatusBar;
    View mKeyguardStatusView;
    KeyguardBottomAreaView mKeyguardBottomArea;
    boolean mLeaveOpenOnKeyguardHide;
    KeyguardIndicationController mKeyguardIndicationController;

    // Keyguard is going away soon.
    private boolean mKeyguardGoingAway;
    // Keyguard is actually fading away now.
    private boolean mKeyguardFadingAway;
    private long mKeyguardFadingAwayDelay;
    private long mKeyguardFadingAwayDuration;

    // RemoteInputView to be activated after unlock
    private View mPendingRemoteInputView;
    private View mPendingWorkRemoteInputView;

    int mMaxAllowedKeyguardNotifications;

    boolean mExpandedVisible;

    private int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    // the tracker view
    int mTrackingPosition; // the position of the top of the tracking view.

    // Tracking finger for opening/closing.
    boolean mTracking;

    int[] mAbsPos = new int[2];
    ArrayList<Runnable> mPostCollapseRunnables = new ArrayList<>();

    // for disabling the status bar
    int mDisabled1 = 0;
    int mDisabled2 = 0;

    // tracking calls to View.setSystemUiVisibility()
    int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;
    private final Rect mLastFullscreenStackBounds = new Rect();
    private final Rect mLastDockedStackBounds = new Rect();

    // last value sent to window manager
    private int mLastDispatchedSystemUiVisibility = ~View.SYSTEM_UI_FLAG_VISIBLE;

    DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private ScreenPinningRequest mScreenPinningRequest;

    private int mNavigationIconHints = 0;
    private HandlerThread mHandlerThread;

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    private boolean mUserSetup = false;
    private ContentObserver mUserSetupObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean userSetup = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE,
                    0 /*default */,
                    mCurrentUserId);
            if (MULTIUSER_DEBUG) Log.d(TAG, String.format("User setup changed: " +
                    "selfChange=%s userSetup=%s mUserSetup=%s",
                    selfChange, userSetup, mUserSetup));

            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mStatusBarView != null)
                    animateCollapseQuickSettings();
                if (mKeyguardBottomArea != null) {
                    mKeyguardBottomArea.setUserSetupComplete(mUserSetup);
                }
                if (mNetworkController != null) {
                    mNetworkController.setUserSetupComplete(mUserSetup);
                }
            }
            if (mIconPolicy != null) {
                mIconPolicy.setCurrentUserSetup(mUserSetup);
            }
        }
    };

    final private ContentObserver mHeadsUpObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            boolean wasUsing = mUseHeadsUp;
            mUseHeadsUp = ENABLE_HEADS_UP && !mDisableNotificationAlerts
                    && Settings.Global.HEADS_UP_OFF != Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    Settings.Global.HEADS_UP_OFF);
            mHeadsUpTicker = mUseHeadsUp && 0 != Settings.Global.getInt(
                    mContext.getContentResolver(), SETTING_HEADS_UP_TICKER, 0);
            Log.d(TAG, "heads up is " + (mUseHeadsUp ? "enabled" : "disabled"));
            if (wasUsing != mUseHeadsUp) {
                if (!mUseHeadsUp) {
                    Log.d(TAG, "dismissing any existing heads up notification on disable event");
                    mHeadsUpManager.releaseAllImmediately();
                }
            }
        }
    };

    private int mInteractingWindows;
    private boolean mAutohideSuspended;
    private int mStatusBarMode;
    private int mNavigationBarMode;
    private int mMaxKeyguardNotifications;

    private ViewMediatorCallback mKeyguardViewMediatorCallback;
    protected ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;

    private final Runnable mAutohide = new Runnable() {
        @Override
        public void run() {
            int requested = mSystemUiVisibility & ~STATUS_OR_NAV_TRANSIENT;
            if (mSystemUiVisibility != requested) {
                notifyUiVisibilityChanged(requested);
            }
        }};

    private boolean mWaitingForKeyguardExit;
    private boolean mDozing;
    private boolean mDozingRequested;
    protected boolean mScrimSrcModeEnabled;

    public static final Interpolator ALPHA_IN = Interpolators.ALPHA_IN;
    public static final Interpolator ALPHA_OUT = Interpolators.ALPHA_OUT;

    private BackDropView mBackdrop;
    private ImageView mBackdropFront, mBackdropBack;
    private PorterDuffXfermode mSrcXferMode = new PorterDuffXfermode(PorterDuff.Mode.SRC);
    private PorterDuffXfermode mSrcOverXferMode = new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER);

    private MediaSessionManager mMediaSessionManager;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;
    private MediaController.Callback mMediaListener
            = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (DEBUG_MEDIA) Log.v(TAG, "DEBUG_MEDIA: onPlaybackStateChanged: " + state);
            if (state != null) {
                if (!isPlaybackActive(state.getState())) {
                    clearCurrentMediaNotification();
                    updateMediaMetaData(true, true);
                }
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (DEBUG_MEDIA) Log.v(TAG, "DEBUG_MEDIA: onMetadataChanged: " + metadata);
            mMediaMetadata = metadata;
            updateMediaMetaData(true, true);
        }
    };

    private final OnChildLocationsChangedListener mOnChildLocationsChangedListener =
            new OnChildLocationsChangedListener() {
        @Override
        public void onChildLocationsChanged(NotificationStackScrollLayout stackScrollLayout) {
            userActivity();
        }
    };

    private int mDisabledUnmodified1;
    private int mDisabledUnmodified2;

    /** Keys of notifications currently visible to the user. */
    private final ArraySet<NotificationVisibility> mCurrentlyVisibleNotifications =
            new ArraySet<>();
    private long mLastVisibilityReportUptimeMs;

    private final ShadeUpdates mShadeUpdates = new ShadeUpdates();

    private Runnable mLaunchTransitionEndRunnable;
    private boolean mLaunchTransitionFadingAway;
    private ExpandableNotificationRow mDraggedDownRow;
    private boolean mLaunchCameraOnScreenTurningOn;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
    private int mLastCameraLaunchSource;
    private PowerManager.WakeLock mGestureWakeLock;
    private Vibrator mVibrator;

    // Fingerprint (as computed by getLoggingFingerprint() of the last logged state.
    private int mLastLoggedStateFingerprint;

    /**
     * If set, the device has started going to sleep but isn't fully non-interactive yet.
     */
    protected boolean mStartedGoingToSleep;

    private static final int VISIBLE_LOCATIONS = StackViewState.LOCATION_FIRST_HUN
            | StackViewState.LOCATION_MAIN_AREA;

    private final OnChildLocationsChangedListener mNotificationLocationsChangedListener =
            new OnChildLocationsChangedListener() {
                @Override
                public void onChildLocationsChanged(
                        NotificationStackScrollLayout stackScrollLayout) {
                    if (mHandler.hasCallbacks(mVisibilityReporter)) {
                        // Visibilities will be reported when the existing
                        // callback is executed.
                        return;
                    }
                    // Calculate when we're allowed to run the visibility
                    // reporter. Note that this timestamp might already have
                    // passed. That's OK, the callback will just be executed
                    // ASAP.
                    long nextReportUptimeMs =
                            mLastVisibilityReportUptimeMs + VISIBILITY_REPORT_MIN_DELAY_MS;
                    mHandler.postAtTime(mVisibilityReporter, nextReportUptimeMs);
                }
            };

    // Tracks notifications currently visible in mNotificationStackScroller and
    // emits visibility events via NoMan on changes.
    private final Runnable mVisibilityReporter = new Runnable() {
        private final ArraySet<NotificationVisibility> mTmpNewlyVisibleNotifications =
                new ArraySet<>();
        private final ArraySet<NotificationVisibility> mTmpCurrentlyVisibleNotifications =
                new ArraySet<>();
        private final ArraySet<NotificationVisibility> mTmpNoLongerVisibleNotifications =
                new ArraySet<>();

        @Override
        public void run() {
            mLastVisibilityReportUptimeMs = SystemClock.uptimeMillis();
            final String mediaKey = getCurrentMediaNotificationKey();

            // 1. Loop over mNotificationData entries:
            //   A. Keep list of visible notifications.
            //   B. Keep list of previously hidden, now visible notifications.
            // 2. Compute no-longer visible notifications by removing currently
            //    visible notifications from the set of previously visible
            //    notifications.
            // 3. Report newly visible and no-longer visible notifications.
            // 4. Keep currently visible notifications for next report.
            ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
            int N = activeNotifications.size();
            for (int i = 0; i < N; i++) {
                Entry entry = activeNotifications.get(i);
                String key = entry.notification.getKey();
                boolean isVisible =
                        (mStackScroller.getChildLocation(entry.row) & VISIBLE_LOCATIONS) != 0;
                NotificationVisibility visObj = NotificationVisibility.obtain(key, i, isVisible);
                boolean previouslyVisible = mCurrentlyVisibleNotifications.contains(visObj);
                if (isVisible) {
                    // Build new set of visible notifications.
                    mTmpCurrentlyVisibleNotifications.add(visObj);
                    if (!previouslyVisible) {
                        mTmpNewlyVisibleNotifications.add(visObj);
                    }
                } else {
                    // release object
                    visObj.recycle();
                }
            }
            mTmpNoLongerVisibleNotifications.addAll(mCurrentlyVisibleNotifications);
            mTmpNoLongerVisibleNotifications.removeAll(mTmpCurrentlyVisibleNotifications);

            logNotificationVisibilityChanges(
                    mTmpNewlyVisibleNotifications, mTmpNoLongerVisibleNotifications);

            recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
            mCurrentlyVisibleNotifications.addAll(mTmpCurrentlyVisibleNotifications);

            recycleAllVisibilityObjects(mTmpNoLongerVisibleNotifications);
            mTmpCurrentlyVisibleNotifications.clear();
            mTmpNewlyVisibleNotifications.clear();
            mTmpNoLongerVisibleNotifications.clear();
        }
    };

    private void recycleAllVisibilityObjects(ArraySet<NotificationVisibility> array) {
        final int N = array.size();
        for (int i = 0 ; i < N; i++) {
            array.valueAt(i).recycle();
        }
        array.clear();
    }

    private final View.OnClickListener mOverflowClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            goToLockedShade(null);
        }
    };
    private HashMap<ExpandableNotificationRow, List<ExpandableNotificationRow>> mTmpChildOrderMap
            = new HashMap<>();
    private RankingMap mLatestRankingMap;
    private boolean mNoAnimationOnNextBarModeChange;
    private FalsingManager mFalsingManager;

    @Override
    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        updateDisplaySize();
        mScrimSrcModeEnabled = mContext.getResources().getBoolean(
                R.bool.config_status_bar_scrim_behind_use_src);

        super.start(); // calls createAndAddWindows()

        mMediaSessionManager
                = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        // TODO: use MediaSessionManager.SessionListener to hook us up to future updates
        // in session state

        addNavigationBar();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext, mIconController, mCastController,
                mHotspotController, mUserInfoController, mBluetoothController,
                mRotationLockController, mNetworkController.getDataSaverController());
        mIconPolicy.setCurrentUserSetup(mUserSetup);
        mSettingsObserver.onChange(false); // set up

        mHeadsUpObserver.onChange(true); // set up
        if (ENABLE_HEADS_UP) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED), true,
                    mHeadsUpObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING_HEADS_UP_TICKER), true,
                    mHeadsUpObserver);
        }
        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mUnlockMethodCache.addListener(this);
        startKeyguard();

        mDozeServiceHost = new DozeServiceHost();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mDozeServiceHost);
        putComponent(DozeHost.class, mDozeServiceHost);
        putComponent(PhoneStatusBar.class, this);

        setControllerUsers();

        notifyUserAboutHiddenNotifications();

        mScreenPinningRequest = new ScreenPinningRequest(mContext);
        mFalsingManager = FalsingManager.getInstance(mContext);
    }

    protected void createIconController() {
        mIconController = new StatusBarIconController(
                mContext, mStatusBarView, mKeyguardStatusBar, this);
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected PhoneStatusBarView makeStatusBarView() {
        final Context context = mContext;

        updateDisplaySize(); // populates mDisplayMetrics
        updateResources();

        inflateStatusBarWindow(context);
        mStatusBarWindow.setService(this);
        mStatusBarWindow.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                checkUserAutohide(v, event);
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mExpandedVisible) {
                        animateCollapsePanels();
                    }
                }
                return mStatusBarWindow.onTouchEvent(event);
            }
        });

        mNotificationPanel = (NotificationPanelView) mStatusBarWindow.findViewById(
                R.id.notification_panel);
        mNotificationPanel.setStatusBar(this);
        mNotificationPanel.setGroupManager(mGroupManager);

        mStatusBarView = (PhoneStatusBarView) mStatusBarWindow.findViewById(R.id.status_bar);
        mStatusBarView.setBar(this);
        mStatusBarView.setPanel(mNotificationPanel);

        if (!ActivityManager.isHighEndGfx()) {
            mStatusBarWindow.setBackground(null);
            mNotificationPanel.setBackground(new FastColorDrawable(context.getColor(
                    R.color.notification_panel_solid_background)));
        }

        mHeadsUpManager = new HeadsUpManager(context, mStatusBarWindow, mGroupManager);
        mHeadsUpManager.setBar(this);
        mHeadsUpManager.addListener(this);
        mHeadsUpManager.addListener(mNotificationPanel);
        mHeadsUpManager.addListener(mGroupManager);
        mNotificationPanel.setHeadsUpManager(mHeadsUpManager);
        mNotificationData.setHeadsUpManager(mHeadsUpManager);
        mGroupManager.setHeadsUpManager(mHeadsUpManager);

        if (MULTIUSER_DEBUG) {
            mNotificationPanelDebugText = (TextView) mNotificationPanel.findViewById(
                    R.id.header_debug_info);
            mNotificationPanelDebugText.setVisibility(View.VISIBLE);
        }

        try {
            boolean showNav = mWindowManagerService.hasNavigationBar();
            if (DEBUG) Log.v(TAG, "hasNavigationBar=" + showNav);
            if (showNav) {
                createNavigationBarView(context);
            }
        } catch (RemoteException ex) {
            // no window manager? good luck with that
        }

        mAssistManager = new AssistManager(this, context);

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.OPAQUE;

        mStackScroller = (NotificationStackScrollLayout) mStatusBarWindow.findViewById(
                R.id.notification_stack_scroller);
        mStackScroller.setLongPressListener(getNotificationLongClicker());
        mStackScroller.setPhoneStatusBar(this);
        mStackScroller.setGroupManager(mGroupManager);
        mStackScroller.setHeadsUpManager(mHeadsUpManager);
        mGroupManager.setOnGroupChangeListener(mStackScroller);

        inflateOverflowContainer();
        inflateEmptyShadeView();
        inflateDismissView();
        mExpandedContents = mStackScroller;

        mBackdrop = (BackDropView) mStatusBarWindow.findViewById(R.id.backdrop);
        mBackdropFront = (ImageView) mBackdrop.findViewById(R.id.backdrop_front);
        mBackdropBack = (ImageView) mBackdrop.findViewById(R.id.backdrop_back);

        ScrimView scrimBehind = (ScrimView) mStatusBarWindow.findViewById(R.id.scrim_behind);
        ScrimView scrimInFront = (ScrimView) mStatusBarWindow.findViewById(R.id.scrim_in_front);
        View headsUpScrim = mStatusBarWindow.findViewById(R.id.heads_up_scrim);
        mScrimController = SystemUIFactory.getInstance().createScrimController(
                scrimBehind, scrimInFront, headsUpScrim);
        if (mScrimSrcModeEnabled) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    boolean asSrc = mBackdrop.getVisibility() != View.VISIBLE;
                    mScrimController.setDrawBehindAsSrc(asSrc);
                    mStackScroller.setDrawBackgroundAsSrc(asSrc);
                }
            };
            mBackdrop.setOnVisibilityChangedRunnable(runnable);
            runnable.run();
        }
        mHeadsUpManager.addListener(mScrimController);
        mStackScroller.setScrimController(mScrimController);
        mStatusBarView.setScrimController(mScrimController);
        mDozeScrimController = new DozeScrimController(mScrimController, context);

        mKeyguardStatusBar = (KeyguardStatusBarView) mStatusBarWindow.findViewById(R.id.keyguard_header);
        mKeyguardStatusView = mStatusBarWindow.findViewById(R.id.keyguard_status_view);
        mKeyguardBottomArea =
                (KeyguardBottomAreaView) mStatusBarWindow.findViewById(R.id.keyguard_bottom_area);
        mKeyguardBottomArea.setActivityStarter(this);
        mKeyguardBottomArea.setAssistManager(mAssistManager);
        mKeyguardIndicationController = new KeyguardIndicationController(mContext,
                (KeyguardIndicationTextView) mStatusBarWindow.findViewById(
                        R.id.keyguard_indication_text),
                mKeyguardBottomArea.getLockIcon());
        mKeyguardBottomArea.setKeyguardIndicationController(mKeyguardIndicationController);

        if (ENABLE_LOCKSCREEN_WALLPAPER) {
            mLockscreenWallpaper = new LockscreenWallpaper(mContext, this, mHandler);
        }

        // set the initial view visibility
        setAreThereNotifications();

        createIconController();

        // Background thread for any controllers that need it.
        mHandlerThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();

        // Other icons
        mLocationController = new LocationControllerImpl(mContext,
                mHandlerThread.getLooper()); // will post a notification
        mBatteryController = createBatteryController();
        mBatteryController.addStateChangedCallback(new BatteryStateChangeCallback() {
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
        mNetworkController = new NetworkControllerImpl(mContext, mHandlerThread.getLooper());
        mNetworkController.setUserSetupComplete(mUserSetup);
        mHotspotController = new HotspotControllerImpl(mContext);
        mBluetoothController = new BluetoothControllerImpl(mContext, mHandlerThread.getLooper());
        mSecurityController = new SecurityControllerImpl(mContext);
        if (mContext.getResources().getBoolean(R.bool.config_showRotationLock)) {
            mRotationLockController = new RotationLockControllerImpl(mContext);
        }
        mUserInfoController = new UserInfoController(mContext);
        mVolumeComponent = getComponent(VolumeComponent.class);
        if (mVolumeComponent != null) {
            mZenModeController = mVolumeComponent.getZenController();
        }
        mCastController = new CastControllerImpl(mContext);

        initSignalCluster(mStatusBarView);
        initSignalCluster(mKeyguardStatusBar);

        mFlashlightController = new FlashlightController(mContext);
        mKeyguardBottomArea.setFlashlightController(mFlashlightController);
        mKeyguardBottomArea.setPhoneStatusBar(this);
        mKeyguardBottomArea.setUserSetupComplete(mUserSetup);
        mAccessibilityController = new AccessibilityController(mContext);
        mKeyguardBottomArea.setAccessibilityController(mAccessibilityController);
        mNextAlarmController = new NextAlarmController(mContext);
        mLightStatusBarController = new LightStatusBarController(mIconController,
                mBatteryController);
        mKeyguardMonitor = new KeyguardMonitor(mContext);
        if (UserManager.get(mContext).isUserSwitcherEnabled()) {
            mUserSwitcherController = new UserSwitcherController(mContext, mKeyguardMonitor,
                    mHandler, this);
            createUserSwitcher();
        }

        // Set up the quick settings tile panel
        AutoReinflateContainer container = (AutoReinflateContainer) mStatusBarWindow.findViewById(
                R.id.qs_auto_reinflate_container);
        if (container != null) {
            final QSTileHost qsh = SystemUIFactory.getInstance().createQSTileHost(mContext, this,
                    mBluetoothController, mLocationController, mRotationLockController,
                    mNetworkController, mZenModeController, mHotspotController,
                    mCastController, mFlashlightController,
                    mUserSwitcherController, mUserInfoController, mKeyguardMonitor,
                    mSecurityController, mBatteryController, mIconController,
                    mNextAlarmController);
            mBrightnessMirrorController = new BrightnessMirrorController(mStatusBarWindow);
            container.addInflateListener(new InflateListener() {
                @Override
                public void onInflated(View v) {
                    QSContainer qsContainer = (QSContainer) v.findViewById(
                            R.id.quick_settings_container);
                    qsContainer.setHost(qsh);
                    mQSPanel = qsContainer.getQsPanel();
                    mQSPanel.setBrightnessMirror(mBrightnessMirrorController);
                    mKeyguardStatusBar.setQSPanel(mQSPanel);
                    mHeader = qsContainer.getHeader();
                    initSignalCluster(mHeader);
                    mHeader.setActivityStarter(PhoneStatusBar.this);
                }
            });
        }

        // User info. Trigger first load.
        mKeyguardStatusBar.setUserInfoController(mUserInfoController);
        mKeyguardStatusBar.setUserSwitcherController(mUserSwitcherController);
        mUserInfoController.reloadUserInfo();

        ((BatteryMeterView) mStatusBarView.findViewById(R.id.battery)).setBatteryController(
                mBatteryController);
        mKeyguardStatusBar.setBatteryController(mBatteryController);

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBroadcastReceiver.onReceive(mContext,
                new Intent(pm.isScreenOn() ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF));
        mGestureWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "GestureWakeLock");
        mVibrator = mContext.getSystemService(Vibrator.class);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);

        IntentFilter demoFilter = new IntentFilter();
        if (DEBUG_MEDIA_FAKE_ARTWORK) {
            demoFilter.addAction(ACTION_FAKE_ARTWORK);
        }
        demoFilter.addAction(ACTION_DEMO);
        context.registerReceiverAsUser(mDemoReceiver, UserHandle.ALL, demoFilter,
                android.Manifest.permission.DUMP, null);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        resetUserSetupObserver();

        // disable profiling bars, since they overlap and clutter the output on app windows
        ThreadedRenderer.overrideProperty("disableProfileBars", "true");

        // Private API call to make the shadows look better for Recents
        ThreadedRenderer.overrideProperty("ambientRatio", String.valueOf(1.5f));

        return mStatusBarView;
    }

    protected BatteryController createBatteryController() {
        return new BatteryControllerImpl(mContext);
    }

    private void inflateOverflowContainer() {
        mKeyguardIconOverflowContainer =
                (NotificationOverflowContainer) LayoutInflater.from(mContext).inflate(
                        R.layout.status_bar_notification_keyguard_overflow, mStackScroller, false);
        mKeyguardIconOverflowContainer.setOnActivatedListener(this);
        mKeyguardIconOverflowContainer.setOnClickListener(mOverflowClickListener);
        mStackScroller.setOverflowContainer(mKeyguardIconOverflowContainer);
    }

    @Override
    protected void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        mScrimController.onDensityOrFontScaleChanged();
        mStatusBarView.onDensityOrFontScaleChanged();
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.onDensityOrFontScaleChanged();
        }
        inflateSignalClusters();
        mIconController.onDensityOrFontScaleChanged();
        inflateDismissView();
        updateClearAll();
        inflateEmptyShadeView();
        updateEmptyShadeView();
        inflateOverflowContainer();
        mStatusBarKeyguardViewManager.onDensityOrFontScaleChanged();
        mUserInfoController.onDensityOrFontScaleChanged();
        if (mUserSwitcherController != null) {
            mUserSwitcherController.onDensityOrFontScaleChanged();
        }
        if (mKeyguardUserSwitcher != null) {
            mKeyguardUserSwitcher.onDensityOrFontScaleChanged();
        }
    }

    private void inflateSignalClusters() {
        SignalClusterView signalClusterView = reinflateSignalCluster(mStatusBarView);
        mIconController.setSignalCluster(signalClusterView);
        reinflateSignalCluster(mKeyguardStatusBar);
    }

    private SignalClusterView reinflateSignalCluster(View view) {
        SignalClusterView signalCluster =
                (SignalClusterView) view.findViewById(R.id.signal_cluster);
        if (signalCluster != null) {
            ViewParent parent = signalCluster.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup viewParent = (ViewGroup) parent;
                int index = viewParent.indexOfChild(signalCluster);
                viewParent.removeView(signalCluster);
                SignalClusterView newCluster = (SignalClusterView) LayoutInflater.from(mContext)
                        .inflate(R.layout.signal_cluster_view, viewParent, false);
                ViewGroup.MarginLayoutParams layoutParams =
                        (ViewGroup.MarginLayoutParams) viewParent.getLayoutParams();
                layoutParams.setMarginsRelative(
                        mContext.getResources().getDimensionPixelSize(
                                R.dimen.signal_cluster_margin_start),
                        0, 0, 0);
                newCluster.setLayoutParams(layoutParams);
                newCluster.setSecurityController(mSecurityController);
                newCluster.setNetworkController(mNetworkController);
                viewParent.addView(newCluster, index);
                return newCluster;
            }
            return signalCluster;
        }
        return null;
    }

    private void inflateEmptyShadeView() {
        mEmptyShadeView = (EmptyShadeView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_no_notifications, mStackScroller, false);
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
    }

    private void inflateDismissView() {
        mDismissView = (DismissView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_notification_dismiss_all, mStackScroller, false);
        mDismissView.setOnButtonClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MetricsLogger.action(mContext, MetricsEvent.ACTION_DISMISS_ALL_NOTES);
                clearAllNotifications();
            }
        });
        mStackScroller.setDismissView(mDismissView);
    }

    protected void createUserSwitcher() {
        mKeyguardUserSwitcher = new KeyguardUserSwitcher(mContext,
                (ViewStub) mStatusBarWindow.findViewById(R.id.keyguard_user_switcher),
                mKeyguardStatusBar, mNotificationPanel, mUserSwitcherController);
    }

    protected void inflateStatusBarWindow(Context context) {
        mStatusBarWindow = (StatusBarWindowView) View.inflate(context,
                R.layout.super_status_bar, null);
    }

    protected void createNavigationBarView(Context context) {
        inflateNavigationBarView(context);
        mNavigationBarView.setDisabledFlags(mDisabled1);
        mNavigationBarView.setComponents(mRecents, getComponent(Divider.class));
        mNavigationBarView.setOnVerticalChangedListener(
                new NavigationBarView.OnVerticalChangedListener() {
            @Override
            public void onVerticalChanged(boolean isVertical) {
                if (mAssistManager != null) {
                    mAssistManager.onConfigurationChanged();
                }
                mNotificationPanel.setQsScrimEnabled(!isVertical);
            }
        });
        mNavigationBarView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                checkUserAutohide(v, event);
                return false;
            }});
    }

    protected void inflateNavigationBarView(Context context) {
        mNavigationBarView = (NavigationBarView) View.inflate(
                context, R.layout.navigation_bar, null);
    }

    protected void initSignalCluster(View containerView) {
        SignalClusterView signalCluster =
                (SignalClusterView) containerView.findViewById(R.id.signal_cluster);
        if (signalCluster != null) {
            signalCluster.setSecurityController(mSecurityController);
            signalCluster.setNetworkController(mNetworkController);
        }
    }

    public void clearAllNotifications() {

        // animate-swipe all dismissable notifications, then animate the shade closed
        int numChildren = mStackScroller.getChildCount();

        final ArrayList<View> viewsToHide = new ArrayList<View>(numChildren);
        for (int i = 0; i < numChildren; i++) {
            final View child = mStackScroller.getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                if (mStackScroller.canChildBeDismissed(child)) {
                    if (child.getVisibility() == View.VISIBLE) {
                        viewsToHide.add(child);
                    }
                }
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                List<ExpandableNotificationRow> children = row.getNotificationChildren();
                if (row.areChildrenExpanded() && children != null) {
                    for (ExpandableNotificationRow childRow : children) {
                        if (childRow.getVisibility() == View.VISIBLE) {
                            viewsToHide.add(childRow);
                        }
                    }
                }
            }
        }
        if (viewsToHide.isEmpty()) {
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
            return;
        }

        addPostCollapseAction(new Runnable() {
            @Override
            public void run() {
                mStackScroller.setDismissAllInProgress(false);
                try {
                    mBarService.onClearAllNotifications(mCurrentUserId);
                } catch (Exception ex) { }
            }
        });

        performDismissAllAnimations(viewsToHide);

    }

    private void performDismissAllAnimations(ArrayList<View> hideAnimatedList) {
        Runnable animationFinishAction = new Runnable() {
            @Override
            public void run() {
                animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
            }
        };

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

    @Override
    protected void setZenMode(int mode) {
        super.setZenMode(mode);
        if (mIconPolicy != null) {
            mIconPolicy.setZenMode(mode);
        }
    }

    protected void startKeyguard() {
        KeyguardViewMediator keyguardViewMediator = getComponent(KeyguardViewMediator.class);
        mFingerprintUnlockController = new FingerprintUnlockController(mContext,
                mStatusBarWindowManager, mDozeScrimController, keyguardViewMediator,
                mScrimController, this);
        mStatusBarKeyguardViewManager = keyguardViewMediator.registerStatusBar(this,
                getBouncerContainer(), mStatusBarWindowManager, mScrimController,
                mFingerprintUnlockController);
        mKeyguardIndicationController.setStatusBarKeyguardViewManager(
                mStatusBarKeyguardViewManager);
        mFingerprintUnlockController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mIconPolicy.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mRemoteInputController.addCallback(mStatusBarKeyguardViewManager);

        mRemoteInputController.addCallback(new RemoteInputController.Callback() {
            @Override
            public void onRemoteInputSent(Entry entry) {
                if (FORCE_REMOTE_INPUT_HISTORY && mKeysKeptForRemoteInput.contains(entry.key)) {
                    removeNotification(entry.key, null);
                } else if (mRemoteInputEntriesToRemoveOnCollapse.contains(entry)) {
                    // We're currently holding onto this notification, but from the apps point of
                    // view it is already canceled, so we'll need to cancel it on the apps behalf
                    // after sending - unless the app posts an update in the mean time, so wait a
                    // bit.
                    mHandler.postDelayed(() -> {
                        if (mRemoteInputEntriesToRemoveOnCollapse.remove(entry)) {
                            removeNotification(entry.key, null);
                        }
                    }, REMOTE_INPUT_KEPT_ENTRY_AUTO_CANCEL_DELAY);
                }
            }
        });

        mKeyguardViewMediatorCallback = keyguardViewMediator.getViewMediatorCallback();
        mLightStatusBarController.setFingerprintUnlockController(mFingerprintUnlockController);
    }

    @Override
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

    private View.OnClickListener mRecentsClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            awakenDreams();
            toggleRecentApps();
        }
    };

    private View.OnLongClickListener mLongPressBackListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            return handleLongPressBack();
        }
    };

    private View.OnLongClickListener mRecentsLongClickListener = new View.OnLongClickListener() {

        @Override
        public boolean onLongClick(View v) {
            if (mRecents == null || !ActivityManager.supportsMultiWindow()
                    || !getComponent(Divider.class).getView().getSnapAlgorithm()
                            .isSplitScreenFeasible()) {
                return false;
            }

            toggleSplitScreenMode(MetricsEvent.ACTION_WINDOW_DOCK_LONGPRESS,
                    MetricsEvent.ACTION_WINDOW_UNDOCK_LONGPRESS);
            return true;
        }
    };

    @Override
    protected void toggleSplitScreenMode(int metricsDockAction, int metricsUndockAction) {
        if (mRecents == null) {
            return;
        }
        int dockSide = WindowManagerProxy.getInstance().getDockSide();
        if (dockSide == WindowManager.DOCKED_INVALID) {
            mRecents.dockTopTask(NavigationBarGestureHelper.DRAG_MODE_NONE,
                    ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT, null, metricsDockAction);
        } else {
            EventBus.getDefault().send(new UndockingTaskEvent());
            if (metricsUndockAction != -1) {
                MetricsLogger.action(mContext, metricsUndockAction);
            }
        }
    }

    private final View.OnLongClickListener mLongPressHomeListener
            = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (shouldDisableNavbarGestures()) {
                return false;
            }
            MetricsLogger.action(mContext, MetricsEvent.ACTION_ASSIST_LONG_PRESS);
            mAssistManager.startAssist(new Bundle() /* args */);
            awakenDreams();
            if (mNavigationBarView != null) {
                mNavigationBarView.abortCurrentGesture();
            }
            return true;
        }
    };

    private final View.OnTouchListener mHomeActionListener = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    awakenDreams();
                    break;
            }
            return false;
        }
    };

    private void awakenDreams() {
        if (mDreamManager != null) {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                // fine, stay asleep then
            }
        }
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();

        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        recentsButton.setOnClickListener(mRecentsClickListener);
        recentsButton.setOnTouchListener(mRecentsPreloadOnTouchListener);
        recentsButton.setLongClickable(true);
        recentsButton.setOnLongClickListener(mRecentsLongClickListener);

        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        backButton.setLongClickable(true);
        backButton.setOnLongClickListener(mLongPressBackListener);

        ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        homeButton.setOnTouchListener(mHomeActionListener);
        homeButton.setOnLongClickListener(mLongPressHomeListener);

        mAssistManager.onConfigurationChanged();
    }

    // For small-screen devices (read: phones) that lack hardware navigation buttons
    protected void addNavigationBar() {
        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + mNavigationBarView);
        if (mNavigationBarView == null) return;

        prepareNavigationBarView();

        mWindowManager.addView(mNavigationBarView, getNavigationBarLayoutParams());
    }

    protected void repositionNavigationBar() {
        if (mNavigationBarView == null || !mNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout(mNavigationBarView, getNavigationBarLayoutParams());
    }

    private void notifyNavigationBarScreenOn(boolean screenOn) {
        if (mNavigationBarView == null) return;
        mNavigationBarView.notifyScreenOn(screenOn);
    }

    private WindowManager.LayoutParams getNavigationBarLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    0
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        // this will allow the navbar to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.setTitle("NavigationBar");
        lp.windowAnimations = 0;
        return lp;
    }

    @Override
    public void setIcon(String slot, StatusBarIcon icon) {
        mIconController.setIcon(slot, icon);
    }

    @Override
    public void removeIcon(String slot) {
        mIconController.removeIcon(slot);
    }

    public UserHandle getCurrentUserHandle() {
        return new UserHandle(mCurrentUserId);
    }

    @Override
    public void addNotification(StatusBarNotification notification, RankingMap ranking,
            Entry oldEntry) {
        if (DEBUG) Log.d(TAG, "addNotification key=" + notification.getKey());

        mNotificationData.updateRanking(ranking);
        Entry shadeEntry = createNotificationViews(notification);
        if (shadeEntry == null) {
            return;
        }
        boolean isHeadsUped = shouldPeek(shadeEntry);
        if (isHeadsUped) {
            mHeadsUpManager.showNotification(shadeEntry);
            // Mark as seen immediately
            setNotificationShown(notification);
        }

        if (!isHeadsUped && notification.getNotification().fullScreenIntent != null) {
            if (shouldSuppressFullScreenIntent(notification.getKey())) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: suppressed by DND: " + notification.getKey());
                }
            } else if (mNotificationData.getImportance(notification.getKey())
                    < NotificationListenerService.Ranking.IMPORTANCE_MAX) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: not important enough: "
                            + notification.getKey());
                }
            } else {
                // Stop screensaver if the notification has a full-screen intent.
                // (like an incoming phone call)
                awakenDreams();

                // not immersive & a full-screen alert should be shown
                if (DEBUG)
                    Log.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                            notification.getKey());
                    notification.getNotification().fullScreenIntent.send();
                    shadeEntry.notifyFullScreenIntentLaunched();
                    MetricsLogger.count(mContext, "note_fullscreen", 1);
                } catch (PendingIntent.CanceledException e) {
                }
            }
        }
        addNotificationViews(shadeEntry, ranking);
        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
    }

    private boolean shouldSuppressFullScreenIntent(String key) {
        if (isDeviceInVrMode()) {
            return true;
        }

        if (mPowerManager.isInteractive()) {
            return mNotificationData.shouldSuppressScreenOn(key);
        } else {
            return mNotificationData.shouldSuppressScreenOff(key);
        }
    }

    @Override
    protected void updateNotificationRanking(RankingMap ranking) {
        mNotificationData.updateRanking(ranking);
        updateNotifications();
    }

    @Override
    public void removeNotification(String key, RankingMap ranking) {
        boolean deferRemoval = false;
        if (mHeadsUpManager.isHeadsUp(key)) {
            // A cancel() in repsonse to a remote input shouldn't be delayed, as it makes the
            // sending look longer than it takes.
            boolean ignoreEarliestRemovalTime = mRemoteInputController.isSpinning(key)
                    && !FORCE_REMOTE_INPUT_HISTORY;
            deferRemoval = !mHeadsUpManager.removeNotification(key,  ignoreEarliestRemovalTime);
        }
        if (key.equals(mMediaNotificationKey)) {
            clearCurrentMediaNotification();
            updateMediaMetaData(true, true);
        }
        if (FORCE_REMOTE_INPUT_HISTORY && mRemoteInputController.isSpinning(key)) {
            Entry entry = mNotificationData.get(key);
            StatusBarNotification sbn = entry.notification;

            Notification.Builder b = Notification.Builder
                    .recoverBuilder(mContext, sbn.getNotification().clone());
            CharSequence[] oldHistory = sbn.getNotification().extras
                    .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY);
            CharSequence[] newHistory;
            if (oldHistory == null) {
                newHistory = new CharSequence[1];
            } else {
                newHistory = new CharSequence[oldHistory.length + 1];
                for (int i = 0; i < oldHistory.length; i++) {
                    newHistory[i + 1] = oldHistory[i];
                }
            }
            newHistory[0] = String.valueOf(entry.remoteInputText);
            b.setRemoteInputHistory(newHistory);

            Notification newNotification = b.build();

            // Undo any compatibility view inflation
            newNotification.contentView = sbn.getNotification().contentView;
            newNotification.bigContentView = sbn.getNotification().bigContentView;
            newNotification.headsUpContentView = sbn.getNotification().headsUpContentView;

            StatusBarNotification newSbn = new StatusBarNotification(sbn.getPackageName(),
                    sbn.getOpPkg(),
                    sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(),
                    0, newNotification, sbn.getUser(), sbn.getPostTime());

            updateNotification(newSbn, null);
            mKeysKeptForRemoteInput.add(entry.key);
            return;
        }
        if (deferRemoval) {
            mLatestRankingMap = ranking;
            mHeadsUpEntriesToRemoveOnSwitch.add(mHeadsUpManager.getEntry(key));
            return;
        }
        Entry entry = mNotificationData.get(key);

        if (entry != null && mRemoteInputController.isRemoteInputActive(entry)) {
            mLatestRankingMap = ranking;
            mRemoteInputEntriesToRemoveOnCollapse.add(entry);
            return;
        }

        if (entry != null && entry.row != null) {
            entry.row.setRemoved();
        }
        // Let's remove the children if this was a summary
        handleGroupSummaryRemoved(key, ranking);
        StatusBarNotification old = removeNotificationViews(key, ranking);
        if (SPEW) Log.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            if (CLOSE_PANEL_WHEN_EMPTIED && !hasActiveNotifications()
                    && !mNotificationPanel.isTracking() && !mNotificationPanel.isQsExpanded()) {
                if (mState == StatusBarState.SHADE) {
                    animateCollapsePanels();
                } else if (mState == StatusBarState.SHADE_LOCKED) {
                    goToKeyguard();
                }
            }
        }
        setAreThereNotifications();
    }

    /**
     * Ensures that the group children are cancelled immediately when the group summary is cancelled
     * instead of waiting for the notification manager to send all cancels. Otherwise this could
     * lead to flickers.
     *
     * This also ensures that the animation looks nice and only consists of a single disappear
     * animation instead of multiple.
     *
     * @param key the key of the notification was removed
     * @param ranking the current ranking
     */
    private void handleGroupSummaryRemoved(String key,
            RankingMap ranking) {
        Entry entry = mNotificationData.get(key);
        if (entry != null && entry.row != null
                && entry.row.isSummaryWithChildren()) {
            if (entry.notification.getOverrideGroupKey() != null && !entry.row.isDismissed()) {
                // We don't want to remove children for autobundled notifications as they are not
                // always cancelled. We only remove them if they were dismissed by the user.
                return;
            }
            List<ExpandableNotificationRow> notificationChildren =
                    entry.row.getNotificationChildren();
            ArrayList<ExpandableNotificationRow> toRemove = new ArrayList<>(notificationChildren);
            for (int i = 0; i < toRemove.size(); i++) {
                toRemove.get(i).setKeepInParent(true);
                // we need to set this state earlier as otherwise we might generate some weird
                // animations
                toRemove.get(i).setRemoved();
            }
            for (int i = 0; i < toRemove.size(); i++) {
                removeNotification(toRemove.get(i).getStatusBarNotification().getKey(), ranking);
                // we need to ensure that the view is actually properly removed from the viewstate
                // as this won't happen anymore when kept in the parent.
                mStackScroller.removeViewStateForView(toRemove.get(i));
            }
        }
    }

    @Override
    protected void performRemoveNotification(StatusBarNotification n, boolean removeView) {
        Entry entry = mNotificationData.get(n.getKey());
        if (mRemoteInputController.isRemoteInputActive(entry)) {
            mRemoteInputController.removeRemoteInput(entry);
        }
        super.performRemoveNotification(n, removeView);
    }

    @Override
    protected void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }
    }

    private void updateNotificationShade() {
        if (mStackScroller == null) return;

        // Do not modify the notifications during collapse.
        if (isCollapsing()) {
            addPostCollapseAction(new Runnable() {
                @Override
                public void run() {
                    updateNotificationShade();
                }
            });
            return;
        }

        ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
        ArrayList<ExpandableNotificationRow> toShow = new ArrayList<>(activeNotifications.size());
        final int N = activeNotifications.size();
        for (int i=0; i<N; i++) {
            Entry ent = activeNotifications.get(i);
            int vis = ent.notification.getNotification().visibility;

            // Display public version of the notification if we need to redact.
            final boolean hideSensitive =
                    !userAllowsPrivateNotificationsInPublic(ent.notification.getUserId());
            boolean sensitiveNote = vis == Notification.VISIBILITY_PRIVATE;
            boolean sensitivePackage = packageHasVisibilityOverride(ent.notification.getKey());
            boolean sensitive = (sensitiveNote && hideSensitive) || sensitivePackage;
            boolean showingPublic = sensitive && isLockscreenPublicMode();
            if (showingPublic) {
                updatePublicContentView(ent, ent.notification);
            }
            ent.row.setSensitive(sensitive, hideSensitive);
            if (ent.autoRedacted && ent.legacy) {
                // TODO: Also fade this? Or, maybe easier (and better), provide a dark redacted form
                // for legacy auto redacted notifications.
                if (showingPublic) {
                    ent.row.setShowingLegacyBackground(false);
                } else {
                    ent.row.setShowingLegacyBackground(true);
                }
            }
            if (mGroupManager.isChildInGroupWithSummary(ent.row.getStatusBarNotification())) {
                ExpandableNotificationRow summary = mGroupManager.getGroupSummary(
                        ent.row.getStatusBarNotification());
                List<ExpandableNotificationRow> orderedChildren =
                        mTmpChildOrderMap.get(summary);
                if (orderedChildren == null) {
                    orderedChildren = new ArrayList<>();
                    mTmpChildOrderMap.put(summary, orderedChildren);
                }
                orderedChildren.add(ent.row);
            } else {
                toShow.add(ent.row);
            }

        }

        ArrayList<ExpandableNotificationRow> toRemove = new ArrayList<>();
        for (int i=0; i< mStackScroller.getChildCount(); i++) {
            View child = mStackScroller.getChildAt(i);
            if (!toShow.contains(child) && child instanceof ExpandableNotificationRow) {
                toRemove.add((ExpandableNotificationRow) child);
            }
        }

        for (ExpandableNotificationRow remove : toRemove) {
            if (mGroupManager.isChildInGroupWithSummary(remove.getStatusBarNotification())) {
                // we are only transfering this notification to its parent, don't generate an animation
                mStackScroller.setChildTransferInProgress(true);
            }
            if (remove.isSummaryWithChildren()) {
                remove.removeAllChildren();
            }
            mStackScroller.removeView(remove);
            mStackScroller.setChildTransferInProgress(false);
        }

        removeNotificationChildren();

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mStackScroller.addView(v);
            }
        }

        addNotificationChildrenAndSort();

        // So after all this work notifications still aren't sorted correctly.
        // Let's do that now by advancing through toShow and mStackScroller in
        // lock-step, making sure mStackScroller matches what we see in toShow.
        int j = 0;
        for (int i = 0; i < mStackScroller.getChildCount(); i++) {
            View child = mStackScroller.getChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                // We don't care about non-notification views.
                continue;
            }

            ExpandableNotificationRow targetChild = toShow.get(j);
            if (child != targetChild) {
                // Oops, wrong notification at this position. Put the right one
                // here and advance both lists.
                mStackScroller.changeViewPosition(targetChild, i);
            }
            j++;

        }

        // clear the map again for the next usage
        mTmpChildOrderMap.clear();

        updateRowStates();
        updateSpeedbump();
        updateClearAll();
        updateEmptyShadeView();

        updateQsExpansionEnabled();
        mShadeUpdates.check();
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
                && ((mDisabled2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) == 0)
                && !ONLY_CORE_APPS);
    }

    private void addNotificationChildrenAndSort() {
        // Let's now add all notification children which are missing
        boolean orderChanged = false;
        for (int i = 0; i < mStackScroller.getChildCount(); i++) {
            View view = mStackScroller.getChildAt(i);
            if (!(view instanceof ExpandableNotificationRow)) {
                // We don't care about non-notification views.
                continue;
            }

            ExpandableNotificationRow parent = (ExpandableNotificationRow) view;
            List<ExpandableNotificationRow> children = parent.getNotificationChildren();
            List<ExpandableNotificationRow> orderedChildren = mTmpChildOrderMap.get(parent);

            for (int childIndex = 0; orderedChildren != null && childIndex < orderedChildren.size();
                    childIndex++) {
                ExpandableNotificationRow childView = orderedChildren.get(childIndex);
                if (children == null || !children.contains(childView)) {
                    parent.addChildNotification(childView, childIndex);
                    mStackScroller.notifyGroupChildAdded(childView);
                }
            }

            // Finally after removing and adding has been beformed we can apply the order.
            orderChanged |= parent.applyChildOrder(orderedChildren);
        }
        if (orderChanged) {
            mStackScroller.generateChildOrderChangedEvent();
        }
    }

    private void removeNotificationChildren() {
        // First let's remove all children which don't belong in the parents
        ArrayList<ExpandableNotificationRow> toRemove = new ArrayList<>();
        for (int i = 0; i < mStackScroller.getChildCount(); i++) {
            View view = mStackScroller.getChildAt(i);
            if (!(view instanceof ExpandableNotificationRow)) {
                // We don't care about non-notification views.
                continue;
            }

            ExpandableNotificationRow parent = (ExpandableNotificationRow) view;
            List<ExpandableNotificationRow> children = parent.getNotificationChildren();
            List<ExpandableNotificationRow> orderedChildren = mTmpChildOrderMap.get(parent);

            if (children != null) {
                toRemove.clear();
                for (ExpandableNotificationRow childRow : children) {
                    if ((orderedChildren == null
                            || !orderedChildren.contains(childRow))
                            && !childRow.keepInParent()) {
                        toRemove.add(childRow);
                    }
                }
                for (ExpandableNotificationRow remove : toRemove) {
                    parent.removeChildNotification(remove);
                    if (mNotificationData.get(remove.getStatusBarNotification().getKey()) == null) {
                        // We only want to add an animation if the view is completely removed
                        // otherwise it's just a transfer
                        mStackScroller.notifyGroupChildRemoved(remove,
                                parent.getChildrenContainer());
                    }
                }
            }
        }
    }

    @Override
    public void addQsTile(ComponentName tile) {
        mQSPanel.getHost().addTile(tile);
    }

    @Override
    public void remQsTile(ComponentName tile) {
        mQSPanel.getHost().removeTile(tile);
    }

    @Override
    public void clickTile(ComponentName tile) {
        mQSPanel.clickTile(tile);
    }

    private boolean packageHasVisibilityOverride(String key) {
        return mNotificationData.getVisibilityOverride(key) == Notification.VISIBILITY_PRIVATE;
    }

    private void updateClearAll() {
        boolean showDismissView =
                mState != StatusBarState.KEYGUARD &&
                mNotificationData.hasActiveClearableNotifications();
        mStackScroller.updateDismissView(showDismissView);
    }

    private void updateEmptyShadeView() {
        boolean showEmptyShade =
                mState != StatusBarState.KEYGUARD &&
                        mNotificationData.getActiveNotifications().size() == 0;
        mNotificationPanel.setShadeEmpty(showEmptyShade);
    }

    private void updateSpeedbump() {
        int speedbumpIndex = -1;
        int currentIndex = 0;
        final int N = mStackScroller.getChildCount();
        for (int i = 0; i < N; i++) {
            View view = mStackScroller.getChildAt(i);
            if (view.getVisibility() == View.GONE || !(view instanceof ExpandableNotificationRow)) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (mNotificationData.isAmbient(row.getStatusBarNotification().getKey())) {
                speedbumpIndex = currentIndex;
                break;
            }
            currentIndex++;
        }
        mStackScroller.updateSpeedBumpIndex(speedbumpIndex);
    }

    public static boolean isTopLevelChild(Entry entry) {
        return entry.row.getParent() instanceof NotificationStackScrollLayout;
    }

    @Override
    protected void updateNotifications() {
        mNotificationData.filterAndSort();

        updateNotificationShade();
        mIconController.updateNotificationIcons(mNotificationData);
    }

    public void requestNotificationUpdate() {
        updateNotifications();
    }

    @Override
    protected void setAreThereNotifications() {

        if (SPEW) {
            final boolean clearable = hasActiveNotifications() &&
                    mNotificationData.hasActiveClearableNotifications();
            Log.d(TAG, "setAreThereNotifications: N=" +
                    mNotificationData.getActiveNotifications().size() + " any=" +
                    hasActiveNotifications() + " clearable=" + clearable);
        }

        final View nlo = mStatusBarView.findViewById(R.id.notification_lights_out);
        final boolean showDot = hasActiveNotifications() && !areLightsOn();
        if (showDot != (nlo.getAlpha() == 1.0f)) {
            if (showDot) {
                nlo.setAlpha(0f);
                nlo.setVisibility(View.VISIBLE);
            }
            nlo.animate()
                .alpha(showDot?1:0)
                .setDuration(showDot?750:250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(showDot ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        nlo.setVisibility(View.GONE);
                    }
                })
                .start();
        }

        findAndUpdateMediaNotifications();
    }

    public void findAndUpdateMediaNotifications() {
        boolean metaDataChanged = false;

        synchronized (mNotificationData) {
            ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
            final int N = activeNotifications.size();

            // Promote the media notification with a controller in 'playing' state, if any.
            Entry mediaNotification = null;
            MediaController controller = null;
            for (int i = 0; i < N; i++) {
                final Entry entry = activeNotifications.get(i);
                if (isMediaNotification(entry)) {
                    final MediaSession.Token token =
                            entry.notification.getNotification().extras
                            .getParcelable(Notification.EXTRA_MEDIA_SESSION);
                    if (token != null) {
                        MediaController aController = new MediaController(mContext, token);
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            if (DEBUG_MEDIA) {
                                Log.v(TAG, "DEBUG_MEDIA: found mediastyle controller matching "
                                        + entry.notification.getKey());
                            }
                            mediaNotification = entry;
                            controller = aController;
                            break;
                        }
                    }
                }
            }
            if (mediaNotification == null) {
                // Still nothing? OK, let's just look for live media sessions and see if they match
                // one of our notifications. This will catch apps that aren't (yet!) using media
                // notifications.

                if (mMediaSessionManager != null) {
                    final List<MediaController> sessions
                            = mMediaSessionManager.getActiveSessionsForUser(
                                    null,
                                    UserHandle.USER_ALL);

                    for (MediaController aController : sessions) {
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            // now to see if we have one like this
                            final String pkg = aController.getPackageName();

                            for (int i = 0; i < N; i++) {
                                final Entry entry = activeNotifications.get(i);
                                if (entry.notification.getPackageName().equals(pkg)) {
                                    if (DEBUG_MEDIA) {
                                        Log.v(TAG, "DEBUG_MEDIA: found controller matching "
                                            + entry.notification.getKey());
                                    }
                                    controller = aController;
                                    mediaNotification = entry;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (controller != null && !sameSessions(mMediaController, controller)) {
                // We have a new media session
                clearCurrentMediaNotification();
                mMediaController = controller;
                mMediaController.registerCallback(mMediaListener);
                mMediaMetadata = mMediaController.getMetadata();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: insert listener, receive metadata: "
                            + mMediaMetadata);
                }

                if (mediaNotification != null) {
                    mMediaNotificationKey = mediaNotification.notification.getKey();
                    if (DEBUG_MEDIA) {
                        Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                                + mMediaNotificationKey + " controller=" + mMediaController);
                    }
                }
                metaDataChanged = true;
            }
        }

        if (metaDataChanged) {
            updateNotifications();
        }
        updateMediaMetaData(metaDataChanged, true);
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    private boolean isPlaybackActive(int state) {
        if (state != PlaybackState.STATE_STOPPED
                && state != PlaybackState.STATE_ERROR
                && state != PlaybackState.STATE_NONE) {
            return true;
        }
        return false;
    }

    private void clearCurrentMediaNotification() {
        mMediaNotificationKey = null;
        mMediaMetadata = null;
        if (mMediaController != null) {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Disconnecting from old controller: "
                        + mMediaController.getPackageName());
            }
            mMediaController.unregisterCallback(mMediaListener);
        }
        mMediaController = null;
    }

    private boolean sameSessions(MediaController a, MediaController b) {
        if (a == b) return true;
        if (a == null) return false;
        return a.controlsSameSession(b);
    }

    /**
     * Hide the album artwork that is fading out and release its bitmap.
     */
    private Runnable mHideBackdropFront = new Runnable() {
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

    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        if (!SHOW_LOCKSCREEN_MEDIA_ARTWORK) return;

        if (mBackdrop == null) return; // called too early

        if (mLaunchTransitionFadingAway) {
            mBackdrop.setVisibility(View.INVISIBLE);
            return;
        }

        if (DEBUG_MEDIA) {
            Log.v(TAG, "DEBUG_MEDIA: updating album art for notification " + mMediaNotificationKey
                    + " metadata=" + mMediaMetadata
                    + " metaDataChanged=" + metaDataChanged
                    + " state=" + mState);
        }

        Drawable artworkDrawable = null;
        if (mMediaMetadata != null) {
            Bitmap artworkBitmap = null;
            artworkBitmap = mMediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (artworkBitmap == null) {
                artworkBitmap = mMediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
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

        if ((hasArtwork || DEBUG_MEDIA_FAKE_ARTWORK)
                && (mState != StatusBarState.SHADE || allowWhenShade)
                && mFingerprintUnlockController.getMode()
                        != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && !hideBecauseOccluded) {
            // time to show some art!
            if (mBackdrop.getVisibility() != View.VISIBLE) {
                mBackdrop.setVisibility(View.VISIBLE);
                if (allowEnterAnimation) {
                    mBackdrop.animate().alpha(1f).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mStatusBarWindowManager.setBackdropShowing(true);
                        }
                    });
                } else {
                    mBackdrop.animate().cancel();
                    mBackdrop.setAlpha(1f);
                    mStatusBarWindowManager.setBackdropShowing(true);
                }
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
            // need to hide the album art, either because we are unlocked or because
            // the metadata isn't there to support it
            if (mBackdrop.getVisibility() != View.GONE) {
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading out album artwork");
                }
                if (mFingerprintUnlockController.getMode()
                        == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                        || hideBecauseOccluded) {

                    // We are unlocking directly - no animation!
                    mBackdrop.setVisibility(View.GONE);
                    mBackdropBack.setImageDrawable(null);
                    mStatusBarWindowManager.setBackdropShowing(false);
                } else {
                    mStatusBarWindowManager.setBackdropShowing(false);
                    mBackdrop.animate()
                            // Never let the alpha become zero - otherwise the RenderNode
                            // won't draw anything and uninitialized memory will show through
                            // if mScrimSrcModeEnabled. Note that 0.001 is rounded down to 0 in
                            // libhwui.
                            .alpha(0.002f)
                            .setInterpolator(Interpolators.ACCELERATE_DECELERATE)
                            .setDuration(300)
                            .setStartDelay(0)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    mBackdrop.setVisibility(View.GONE);
                                    mBackdropFront.animate().cancel();
                                    mBackdropBack.setImageDrawable(null);
                                    mHandler.post(mHideBackdropFront);
                                }
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
    }

    protected int adjustDisableFlags(int state) {
        if (!mLaunchTransitionFadingAway && !mKeyguardFadingAway
                && (mExpandedVisible || mBouncerShowing || mWaitingForKeyguardExit)) {
            state |= StatusBarManager.DISABLE_NOTIFICATION_ICONS;
            state |= StatusBarManager.DISABLE_SYSTEM_INFO;
        }
        return state;
    }

    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    public void disable(int state1, int state2, boolean animate) {
        animate &= mStatusBarWindowState != WINDOW_STATE_HIDDEN;
        mDisabledUnmodified1 = state1;
        mDisabledUnmodified2 = state2;
        state1 = adjustDisableFlags(state1);
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
        flagdbg.append("disable: < ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_EXPAND) != 0) ? "EXPAND" : "expand");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_EXPAND) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "ICONS" : "icons");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "ALERTS" : "alerts");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "SYSTEM_INFO" : "system_info");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_BACK) != 0) ? "BACK" : "back");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_BACK) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_HOME) != 0) ? "HOME" : "home");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_HOME) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_RECENT) != 0) ? "RECENT" : "recent");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_RECENT) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_CLOCK) != 0) ? "CLOCK" : "clock");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_CLOCK) != 0) ? "* " : " ");
        flagdbg.append(((state1 & StatusBarManager.DISABLE_SEARCH) != 0) ? "SEARCH" : "search");
        flagdbg.append(((diff1  & StatusBarManager.DISABLE_SEARCH) != 0) ? "* " : " ");
        flagdbg.append(((state2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) ? "QUICK_SETTINGS"
                : "quick_settings");
        flagdbg.append(((diff2  & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) ? "* " : " ");
        flagdbg.append(">");
        Log.d(TAG, flagdbg.toString());

        if ((diff1 & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
            if ((state1 & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
                mIconController.hideSystemIconArea(animate);
            } else {
                mIconController.showSystemIconArea(animate);
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_CLOCK) != 0) {
            boolean visible = (state1 & StatusBarManager.DISABLE_CLOCK) == 0;
            mIconController.setClockVisibility(visible);
        }
        if ((diff1 & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state1 & StatusBarManager.DISABLE_EXPAND) != 0) {
                animateCollapsePanels();
            }
        }

        if ((diff1 & (StatusBarManager.DISABLE_HOME
                        | StatusBarManager.DISABLE_RECENT
                        | StatusBarManager.DISABLE_BACK
                        | StatusBarManager.DISABLE_SEARCH)) != 0) {
            // the nav bar will take care of these
            if (mNavigationBarView != null) mNavigationBarView.setDisabledFlags(state1);

            if ((state1 & StatusBarManager.DISABLE_RECENT) != 0) {
                // close recents if it's visible
                mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
                mHandler.sendEmptyMessage(MSG_HIDE_RECENT_APPS);
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state1 & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                mIconController.hideNotificationIconArea(animate);
            } else {
                mIconController.showNotificationIconArea(animate);
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
            mDisableNotificationAlerts =
                    (state1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
            mHeadsUpObserver.onChange(true);
        }

        if ((diff2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) {
            updateQsExpansionEnabled();
        }
    }

    @Override
    protected BaseStatusBar.H createHandler() {
        return new PhoneStatusBar.H();
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade) {
        startActivityDismissingKeyguard(intent, false, dismissShade);
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        startActivityDismissingKeyguard(intent, false, dismissShade, callback);
    }

    @Override
    public void preventNextAnimation() {
        overrideActivityPendingAppTransition(true /* keyguardShowing */);
    }

    public void setQsExpanded(boolean expanded) {
        mStatusBarWindowManager.setQsExpanded(expanded);
        mKeyguardStatusView.setImportantForAccessibility(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public boolean isGoingToNotificationShade() {
        return mLeaveOpenOnKeyguardHide;
    }

    public boolean isQsExpanded() {
        return mNotificationPanel.isQsExpanded();
    }

    public boolean isWakeUpComingFromTouch() {
        return mWakeUpComingFromTouch;
    }

    public boolean isFalsingThresholdNeeded() {
        return getBarState() == StatusBarState.KEYGUARD;
    }

    public boolean isDozing() {
        return mDozing;
    }

    @Override  // NotificationData.Environment
    public String getCurrentMediaNotificationKey() {
        return mMediaNotificationKey;
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
                mNotificationPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        mStatusBarWindowManager.setForceWindowCollapsed(false);
                    }
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
                mStackScroller.runAfterAnimationFinished(new Runnable() {
                    @Override
                    public void run() {
                        if (!mHeadsUpManager.hasPinnedHeadsUp()) {
                            mStatusBarWindowManager.setHeadsUpShowing(false);
                            mHeadsUpManager.setHeadsUpGoingAway(false);
                        }
                    }
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
        if (!isHeadsUp && mHeadsUpEntriesToRemoveOnSwitch.contains(entry)) {
            removeNotification(entry.key, mLatestRankingMap);
            mHeadsUpEntriesToRemoveOnSwitch.remove(entry);
            if (mHeadsUpEntriesToRemoveOnSwitch.isEmpty()) {
                mLatestRankingMap = null;
            }
        } else {
            updateNotificationRanking(null);
        }

    }

    protected void updateHeadsUp(String key, Entry entry, boolean shouldPeek,
            boolean alertAgain) {
        final boolean wasHeadsUp = isHeadsUp(key);
        if (wasHeadsUp) {
            if (!shouldPeek) {
                // We don't want this to be interrupting anymore, lets remove it
                mHeadsUpManager.removeNotification(key, false /* ignoreEarliestRemovalTime */);
            } else {
                mHeadsUpManager.updateNotification(entry, alertAgain);
            }
        } else if (shouldPeek && alertAgain) {
            // This notification was updated to be a heads-up, show it!
            mHeadsUpManager.showNotification(entry);
        }
    }

    protected void setHeadsUpUser(int newUserId) {
        if (mHeadsUpManager != null) {
            mHeadsUpManager.setUser(newUserId);
        }
    }

    public boolean isHeadsUp(String key) {
        return mHeadsUpManager.isHeadsUp(key);
    }

    protected boolean isSnoozedPackage(StatusBarNotification sbn) {
        return mHeadsUpManager.isSnoozed(sbn.getPackageName());
    }

    public boolean isKeyguardCurrentlySecure() {
        return !mUnlockMethodCache.canSkipBouncer();
    }

    public void setPanelExpanded(boolean isExpanded) {
        mStatusBarWindowManager.setPanelExpanded(isExpanded);

        if (isExpanded && getBarState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from setPanelExpanded");
            }
            clearNotificationEffects();
        }

        if (!isExpanded) {
            removeRemoteInputEntriesKeptUntilCollapsed();
        }
    }

    private void removeRemoteInputEntriesKeptUntilCollapsed() {
        for (int i = 0; i < mRemoteInputEntriesToRemoveOnCollapse.size(); i++) {
            Entry entry = mRemoteInputEntriesToRemoveOnCollapse.valueAt(i);
            mRemoteInputController.removeRemoteInput(entry);
            removeNotification(entry.key, mLatestRankingMap);
        }
        mRemoteInputEntriesToRemoveOnCollapse.clear();
    }

    public void onScreenTurnedOff() {
        mFalsingManager.onScreenOff();
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends BaseStatusBar.H {
        public void handleMessage(Message m) {
            super.handleMessage(m);
            switch (m.what) {
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

    @Override
    public void maybeEscalateHeadsUp() {
        Collection<HeadsUpManager.HeadsUpEntry> entries = mHeadsUpManager.getAllEntries();
        for (HeadsUpManager.HeadsUpEntry entry : entries) {
            final StatusBarNotification sbn = entry.entry.notification;
            final Notification notification = sbn.getNotification();
            if (notification.fullScreenIntent != null) {
                if (DEBUG) {
                    Log.d(TAG, "converting a heads up to fullScreen");
                }
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_HEADS_UP_ESCALATION,
                            sbn.getKey());
                    notification.fullScreenIntent.send();
                    entry.entry.notifyFullScreenIntentLaunched();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        }
        mHeadsUpManager.releaseAllImmediately();
    }

    boolean panelsEnabled() {
        return (mDisabled1 & StatusBarManager.DISABLE_EXPAND) == 0 && !ONLY_CORE_APPS;
    }

    void makeExpandedVisible(boolean force) {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (!force && (mExpandedVisible || !panelsEnabled())) {
            return;
        }

        mExpandedVisible = true;
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(true);

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        mStatusBarWindowManager.setPanelVisible(true);

        visibilityChanged(true);
        mWaitingForKeyguardExit = false;
        disable(mDisabledUnmodified1, mDisabledUnmodified2, !force /* animate */);
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
    }

    public void animateCollapsePanels() {
        animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
    }

    private final Runnable mAnimateCollapsePanels = new Runnable() {
        @Override
        public void run() {
            animateCollapsePanels();
        }
    };

    public void postAnimateCollapsePanels() {
        mHandler.post(mAnimateCollapsePanels);
    }

    public void postAnimateOpenPanels() {
        mHandler.sendEmptyMessage(MSG_OPEN_SETTINGS_PANEL);
    }

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

        if (mStatusBarWindow != null) {
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

    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
            return ;
        }

        mNotificationPanel.expand(true /* animate */);

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
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(false);
        visibilityChanged(false);

        // Shrink the window to the size of the status bar only
        mStatusBarWindowManager.setPanelVisible(false);
        mStatusBarWindowManager.setForceStatusBarVisible(false);

        // Close any "App info" popups that might have snuck on-screen
        dismissPopups();

        runPostCollapseRunnables();
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        showBouncer();
        disable(mDisabledUnmodified1, mDisabledUnmodified2, true /* animate */);

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
                + mDisabled1 + " mDisabled2=" + mDisabled2 + " mTracking=" + mTracking);
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

    private void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;

        if (mNavigationBarView != null) {
            mNavigationBarView.setNavigationIconHints(hints);
        }
        checkBarModes();
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
        }
        if (mNavigationBarView != null
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));
        }
    }

    @Override // CommandQueue
    public void buzzBeepBlinked() {
        if (mDozeServiceHost != null) {
            mDozeServiceHost.fireBuzzBeepBlinked();
        }
    }

    @Override
    public void notificationLightOff() {
        if (mDozeServiceHost != null) {
            mDozeServiceHost.fireNotificationLight(false);
        }
    }

    @Override
    public void notificationLightPulse(int argb, int onMillis, int offMillis) {
        if (mDozeServiceHost != null) {
            mDozeServiceHost.fireNotificationLight(true);
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
            // we never set the recents bit via this method, so save the prior state to prevent
            // clobbering the bit below
            final boolean wasRecentsVisible = (mSystemUiVisibility & View.RECENT_APPS_VISIBLE) > 0;

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
            final int sbMode = computeBarMode(oldVal, newVal, mStatusBarView.getBarTransitions(),
                    View.STATUS_BAR_TRANSIENT, View.STATUS_BAR_TRANSLUCENT,
                    View.STATUS_BAR_TRANSPARENT);

            // update navigation bar mode
            final int nbMode = mNavigationBarView == null ? -1 : computeBarMode(
                    oldVal, newVal, mNavigationBarView.getBarTransitions(),
                    View.NAVIGATION_BAR_TRANSIENT, View.NAVIGATION_BAR_TRANSLUCENT,
                    View.NAVIGATION_BAR_TRANSPARENT);
            sbModeChanged = sbMode != -1;
            final boolean nbModeChanged = nbMode != -1;
            boolean checkBarModes = false;
            if (sbModeChanged && sbMode != mStatusBarMode) {
                mStatusBarMode = sbMode;
                checkBarModes = true;
            }
            if (nbModeChanged && nbMode != mNavigationBarMode) {
                mNavigationBarMode = nbMode;
                checkBarModes = true;
            }
            if (checkBarModes) {
                checkBarModes();
            }
            if (sbModeChanged || nbModeChanged) {
                // update transient bar autohide
                if (mStatusBarMode == MODE_SEMI_TRANSPARENT || mNavigationBarMode == MODE_SEMI_TRANSPARENT) {
                    scheduleAutohide();
                } else {
                    cancelAutohide();
                }
            }

            if ((vis & View.NAVIGATION_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.NAVIGATION_BAR_UNHIDE;
            }

            // restore the recents bit
            if (wasRecentsVisible) {
                mSystemUiVisibility |= View.RECENT_APPS_VISIBLE;
            }

            // send updated sysui visibility to window manager
            notifyUiVisibilityChanged(mSystemUiVisibility);
        }

        mLightStatusBarController.onSystemUiVisibilityChanged(fullscreenStackVis, dockedStackVis,
                mask, fullscreenStackBounds, dockedStackBounds, sbModeChanged, mStatusBarMode);
    }

    private int computeBarMode(int oldVis, int newVis, BarTransitions transitions,
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

    private void checkBarModes() {
        if (mDemoMode) return;
        checkBarMode(mStatusBarMode, mStatusBarWindowState, mStatusBarView.getBarTransitions(),
                mNoAnimationOnNextBarModeChange);
        if (mNavigationBarView != null) {
            checkBarMode(mNavigationBarMode,
                    mNavigationBarWindowState, mNavigationBarView.getBarTransitions(),
                    mNoAnimationOnNextBarModeChange);
        }
        mNoAnimationOnNextBarModeChange = false;
    }

    private void checkBarMode(int mode, int windowState, BarTransitions transitions,
            boolean noAnimation) {
        final boolean powerSave = mBatteryController.isPowerSave();
        final boolean anim = !noAnimation && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN && !powerSave;
        if (powerSave && getBarState() == StatusBarState.SHADE) {
            mode = MODE_WARNING;
        }
        transitions.transitionTo(mode, anim);
    }

    private void finishBarAnimations() {
        mStatusBarView.getBarTransitions().finishAnimations();
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().finishAnimations();
        }
    }

    private final Runnable mCheckBarModes = new Runnable() {
        @Override
        public void run() {
            checkBarModes();
        }
    };

    @Override
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

    private void checkUserAutohide(View v, MotionEvent event) {
        if ((mSystemUiVisibility & STATUS_OR_NAV_TRANSIENT) != 0  // a transient bar is revealed
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && !mRemoteInputController.isRemoteInputActive()) { // not due to typing in IME
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

    public void topAppWindowChanged(boolean showMenu) {
        if (SPEW) {
            Log.d(TAG, (showMenu?"showing":"hiding") + " the MENU button");
        }
        if (mNavigationBarView != null) {
            mNavigationBarView.setMenuVisibility(showMenu);
        }

        // See above re: lights-out policy for legacy apps.
        if (showMenu) setLightsOn(true);
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int flags = mNavigationIconHints;
        if ((backDisposition == InputMethodService.BACK_DISPOSITION_WILL_DISMISS) || imeShown) {
            flags |= NAVIGATION_HINT_BACK_ALT;
        } else {
            flags &= ~NAVIGATION_HINT_BACK_ALT;
        }
        if (showImeSwitcher) {
            flags |= NAVIGATION_HINT_IME_SHOWN;
        } else {
            flags &= ~NAVIGATION_HINT_IME_SHOWN;
        }

        setNavigationIconHints(flags);
    }

    public static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mExpandedVisible
                    + ", mTrackingPosition=" + mTrackingPosition);
            pw.println("  mTracking=" + mTracking);
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
        pw.println(Settings.Global.zenModeToString(mZenMode));
        pw.print("  mUseHeadsUp=");
        pw.println(mUseHeadsUp);
        dumpBarTransitions(pw, "mStatusBarView", mStatusBarView.getBarTransitions());
        if (mNavigationBarView != null) {
            pw.print("  mNavigationBarWindowState=");
            pw.println(windowStateToString(mNavigationBarWindowState));
            pw.print("  mNavigationBarMode=");
            pw.println(BarTransitions.modeToString(mNavigationBarMode));
            dumpBarTransitions(pw, "mNavigationBarView", mNavigationBarView.getBarTransitions());
        }

        pw.print("  mNavigationBarView=");
        if (mNavigationBarView == null) {
            pw.println("null");
        } else {
            mNavigationBarView.dump(fd, pw, args);
        }

        pw.print("  mMediaSessionManager=");
        pw.println(mMediaSessionManager);
        pw.print("  mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("  mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("  mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();

        pw.println("  Panels: ");
        if (mNotificationPanel != null) {
            pw.println("    mNotificationPanel=" +
                mNotificationPanel + " params=" + mNotificationPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanel.dump(fd, pw, args);
        }

        DozeLog.dump(pw);

        if (DUMPTRUCK) {
            synchronized (mNotificationData) {
                mNotificationData.dump(pw, "  ");
            }

            mIconController.dump(pw);

            if (false) {
                pw.println("see the logcat for a dump of the views we have created.");
                // must happen on ui thread
                mHandler.post(new Runnable() {
                        public void run() {
                            mStatusBarView.getLocationOnScreen(mAbsPos);
                            Log.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                    + ") " + mStatusBarView.getWidth() + "x"
                                    + getStatusBarHeight());
                            mStatusBarView.debug();
                        }
                    });
            }
        }

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(fd, pw, args);
        }
        if (mStatusBarWindowManager != null) {
            mStatusBarWindowManager.dump(fd, pw, args);
        }
        if (mNetworkController != null) {
            mNetworkController.dump(fd, pw, args);
        }
        if (mBluetoothController != null) {
            mBluetoothController.dump(fd, pw, args);
        }
        if (mHotspotController != null) {
            mHotspotController.dump(fd, pw, args);
        }
        if (mCastController != null) {
            mCastController.dump(fd, pw, args);
        }
        if (mUserSwitcherController != null) {
            mUserSwitcherController.dump(fd, pw, args);
        }
        if (mBatteryController != null) {
            mBatteryController.dump(fd, pw, args);
        }
        if (mNextAlarmController != null) {
            mNextAlarmController.dump(fd, pw, args);
        }
        if (mSecurityController != null) {
            mSecurityController.dump(fd, pw, args);
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

    private static void dumpBarTransitions(PrintWriter pw, String var, BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        pw.println(BarTransitions.modeToString(transitions.getMode()));
    }

    @Override
    public void createAndAddWindows() {
        addStatusBarWindow();
    }

    private void addStatusBarWindow() {
        makeStatusBarView();
        mStatusBarWindowManager = new StatusBarWindowManager(mContext);
        mRemoteInputController = new RemoteInputController(mStatusBarWindowManager,
                mHeadsUpManager);
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

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            boolean dismissShade) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, dismissShade, null /* callback */);
    }

    public void startActivityDismissingKeyguard(final Intent intent, boolean onlyProvisioned,
            final boolean dismissShade, final Callback callback) {
        if (onlyProvisioned && !isDeviceProvisioned()) return;

        final boolean afterKeyguardGone = PreviewInflater.wouldLaunchResolverActivity(
                mContext, intent, mCurrentUserId);
        final boolean keyguardShowing = mStatusBarKeyguardViewManager.isShowing();
        Runnable runnable = new Runnable() {
            public void run() {
                mAssistManager.hideAssist();
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int result = ActivityManager.START_CANCELED;
                try {
                    result = ActivityManagerNative.getDefault().startActivityAsUser(
                            null, mContext.getBasePackageName(),
                            intent,
                            intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                            null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null,
                            getActivityOptions(), UserHandle.CURRENT.getIdentifier());
                } catch (RemoteException e) {
                    Log.w(TAG, "Unable to start activity", e);
                }
                overrideActivityPendingAppTransition(
                        keyguardShowing && !afterKeyguardGone);
                if (callback != null) {
                    callback.onActivityStarted(result);
                }
            }
        };
        Runnable cancelRunnable = new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onActivityStarted(ActivityManager.START_CANCELED);
                }
            }
        };
        executeRunnableDismissingKeyguard(runnable, cancelRunnable, dismissShade,
                afterKeyguardGone, true /* deferred */);
    }

    public void executeRunnableDismissingKeyguard(final Runnable runnable,
            final Runnable cancelAction,
            final boolean dismissShade,
            final boolean afterKeyguardGone,
            final boolean deferred) {
        final boolean keyguardShowing = mStatusBarKeyguardViewManager.isShowing();
        dismissKeyguardThenExecute(new OnDismissAction() {
            @Override
            public boolean onDismiss() {
                AsyncTask.execute(new Runnable() {
                    public void run() {
                        try {
                            if (keyguardShowing && !afterKeyguardGone) {
                                ActivityManagerNative.getDefault()
                                        .keyguardWaitingForActivityDrawn();
                            }
                            if (runnable != null) {
                                runnable.run();
                            }
                        } catch (RemoteException e) {
                        }
                    }
                });
                if (dismissShade) {
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */,
                            true /* delayed*/);
                }
                return deferred;
            }
        }, cancelAction, afterKeyguardGone);
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                KeyboardShortcuts.dismiss();
                if (mRemoteInputController != null) {
                    mRemoteInputController.closeRemoteInputs();
                }
                if (isCurrentProfile(getSendingUserId())) {
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                    animateCollapsePanels(flags);
                }
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                notifyNavigationBarScreenOn(false);
                notifyHeadsUpScreenOff();
                finishBarAnimations();
                resetUserExpandedStates();
            }
            else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                notifyNavigationBarScreenOn(true);
            }
        }
    };

    private BroadcastReceiver mDemoReceiver = new BroadcastReceiver() {
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
        ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
        final int notificationCount = activeNotifications.size();
        for (int i = 0; i < notificationCount; i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
            if (entry.row != null) {
                entry.row.resetUserExpansion();
            }
        }
    }

    @Override
    protected void dismissKeyguardThenExecute(OnDismissAction action, boolean afterKeyguardGone) {
        dismissKeyguardThenExecute(action, null /* cancelRunnable */, afterKeyguardGone);
    }

    public void dismissKeyguard() {
        mStatusBarKeyguardViewManager.dismiss();
    }

    private void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancelAction,
            boolean afterKeyguardGone) {
        if (mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                    afterKeyguardGone);
        } else {
            action.onDismiss();
        }
    }

    // SystemUIService notifies SystemBars of configuration changes, which then calls down here
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        updateResources();
        updateDisplaySize(); // populates mDisplayMetrics
        super.onConfigurationChanged(newConfig); // calls refreshLayout

        if (DEBUG) {
            Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
        }

        repositionNavigationBar();
        updateRowStates();
        mIconController.defineSlots();
        mScreenPinningRequest.onConfigurationChanged();
        mNetworkController.onConfigurationChanged();
    }

    @Override
    public void userSwitched(int newUserId) {
        super.userSwitched(newUserId);
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        animateCollapsePanels();
        updatePublicMode();
        updateNotifications();
        resetUserSetupObserver();
        setControllerUsers();
        clearCurrentMediaNotification();
        mLockscreenWallpaper.setCurrentUser(newUserId);
        updateMediaMetaData(true, false);
    }

    private void setControllerUsers() {
        if (mZenModeController != null) {
            mZenModeController.setUserId(mCurrentUserId);
        }
        if (mSecurityController != null) {
            mSecurityController.onUserSwitched(mCurrentUserId);
        }
    }

    private void resetUserSetupObserver() {
        mContext.getContentResolver().unregisterContentObserver(mUserSetupObserver);
        mUserSetupObserver.onChange(false);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), true,
                mUserSetupObserver, mCurrentUserId);
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

    @Override
    protected void handleVisibleToUserChanged(boolean visibleToUser) {
        if (visibleToUser) {
            super.handleVisibleToUserChanged(visibleToUser);
            startNotificationLogging();
        } else {
            stopNotificationLogging();
            super.handleVisibleToUserChanged(visibleToUser);
        }
    }

    private void stopNotificationLogging() {
        // Report all notifications as invisible and turn down the
        // reporter.
        if (!mCurrentlyVisibleNotifications.isEmpty()) {
            logNotificationVisibilityChanges(Collections.<NotificationVisibility>emptyList(),
                    mCurrentlyVisibleNotifications);
            recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
        }
        mHandler.removeCallbacks(mVisibilityReporter);
        mStackScroller.setChildLocationsChangedListener(null);
    }

    private void startNotificationLogging() {
        mStackScroller.setChildLocationsChangedListener(mNotificationLocationsChangedListener);
        // Some transitions like mVisibleToUser=false -> mVisibleToUser=true don't
        // cause the scroller to emit child location events. Hence generate
        // one ourselves to guarantee that we're reporting visible
        // notifications.
        // (Note that in cases where the scroller does emit events, this
        // additional event doesn't break anything.)
        mNotificationLocationsChangedListener.onChildLocationsChanged(mStackScroller);
    }

    private void logNotificationVisibilityChanges(
            Collection<NotificationVisibility> newlyVisible,
            Collection<NotificationVisibility> noLongerVisible) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return;
        }
        NotificationVisibility[] newlyVisibleAr =
                newlyVisible.toArray(new NotificationVisibility[newlyVisible.size()]);
        NotificationVisibility[] noLongerVisibleAr =
                noLongerVisible.toArray(new NotificationVisibility[noLongerVisible.size()]);
        try {
            mBarService.onNotificationVisibilityChanged(newlyVisibleAr, noLongerVisibleAr);
        } catch (RemoteException e) {
            // Ignore.
        }

        final int N = newlyVisible.size();
        if (N > 0) {
            String[] newlyVisibleKeyAr = new String[N];
            for (int i = 0; i < N; i++) {
                newlyVisibleKeyAr[i] = newlyVisibleAr[i].key;
            }

            setNotificationsShown(newlyVisibleKeyAr);
        }
    }

    // State logging

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

    Runnable mStartTracing = new Runnable() {
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Log.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        public void run() {
            android.os.Debug.stopMethodTracing();
            Log.d(TAG, "stopTracing");
            vibrate();
        }
    };

    @Override
    public boolean shouldDisableNavbarGestures() {
        return !isDeviceProvisioned() || (mDisabled1 & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    public void postQSRunnableDismissingKeyguard(final Runnable runnable) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mLeaveOpenOnKeyguardHide = true;
                executeRunnableDismissingKeyguard(runnable, null, false, false, false);
            }
        });
    }

    public void postStartActivityDismissingKeyguard(final PendingIntent intent) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                startPendingIntentDismissingKeyguard(intent);
            }
        });
    }

    public void postStartActivityDismissingKeyguard(final Intent intent, int delay) {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                handleStartActivityDismissingKeyguard(intent, true /*onlyProvisioned*/);
            }
        }, delay);
    }

    private void handleStartActivityDismissingKeyguard(Intent intent, boolean onlyProvisioned) {
        startActivityDismissingKeyguard(intent, onlyProvisioned, true /* dismissShade */);
    }

    private static class FastColorDrawable extends Drawable {
        private final int mColor;

        public FastColorDrawable(int color) {
            mColor = 0xff000000 | color;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(mColor, PorterDuff.Mode.SRC);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
        }

        @Override
        public void setBounds(Rect bounds) {
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (mStatusBarWindow != null) {
            mWindowManager.removeViewImmediate(mStatusBarWindow);
            mStatusBarWindow = null;
        }
        if (mNavigationBarView != null) {
            mWindowManager.removeViewImmediate(mNavigationBarView);
            mNavigationBarView = null;
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.unregisterReceiver(mDemoReceiver);
        mAssistManager.destroy();

        final SignalClusterView signalCluster =
                (SignalClusterView) mStatusBarView.findViewById(R.id.signal_cluster);
        final SignalClusterView signalClusterKeyguard =
                (SignalClusterView) mKeyguardStatusBar.findViewById(R.id.signal_cluster);
        final SignalClusterView signalClusterQs =
                (SignalClusterView) mHeader.findViewById(R.id.signal_cluster);
        mNetworkController.removeSignalCallback(signalCluster);
        mNetworkController.removeSignalCallback(signalClusterKeyguard);
        mNetworkController.removeSignalCallback(signalClusterQs);
        if (mQSPanel != null && mQSPanel.getHost() != null) {
            mQSPanel.getHost().destroy();
        }
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
            mIconController.dispatchDemoCommand(command, args);
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
                if (mNavigationBarView != null) {
                    mNavigationBarView.getBarTransitions().transitionTo(barMode, animate);
                }
            }
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
    public boolean isPanelFullyCollapsed() {
        return mNotificationPanel.isFullyCollapsed();
    }

    public void showKeyguard() {
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
        if (!mDeviceInteractive) {

            // If the screen is off already, we need to disable touch events because these might
            // collapse the panel after we expanded it, and thus we would end up with a blank
            // Keyguard.
            mNotificationPanel.setTouchDisabled(true);
        }
        if (mState == StatusBarState.KEYGUARD) {
            instantExpandNotificationsPanel();
        } else if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            instantCollapseNotificationPanel();
        }
        mLeaveOpenOnKeyguardHide = false;
        if (mDraggedDownRow != null) {
            mDraggedDownRow.setUserLocked(false);
            mDraggedDownRow.notifyHeightChanged(false  /* needsAnimation */);
            mDraggedDownRow = null;
        }
        mPendingRemoteInputView = null;
        mAssistManager.onLockscreenShown();
    }

    private void onLaunchTransitionFadingEnded() {
        mNotificationPanel.setAlpha(1.0f);
        mNotificationPanel.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        runLaunchTransitionEndRunnable();
        mLaunchTransitionFadingAway = false;
        mScrimController.forceHideScrims(false /* hide */);
        updateMediaMetaData(true /* metaDataChanged */, true);
    }

    @Override
    public boolean isCollapsing() {
        return mNotificationPanel.isCollapsing();
    }

    @Override
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
        Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                mLaunchTransitionFadingAway = true;
                if (beforeFading != null) {
                    beforeFading.run();
                }
                mScrimController.forceHideScrims(true /* hide */);
                updateMediaMetaData(false, true);
                mNotificationPanel.setAlpha(1);
                mStackScroller.setParentFadingOut(true);
                mNotificationPanel.animate()
                        .alpha(0)
                        .setStartDelay(FADE_KEYGUARD_START_DELAY)
                        .setDuration(FADE_KEYGUARD_DURATION)
                        .withLayer()
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                onLaunchTransitionFadingEnded();
                            }
                        });
                mIconController.appTransitionStarting(SystemClock.uptimeMillis(),
                        StatusBarIconController.DEFAULT_TINT_ANIMATION_DURATION);
            }
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
                .setInterpolator(ScrimController.KEYGUARD_FADE_OUT_INTERPOLATOR)
                .start();
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
    public boolean hideKeyguard() {
        boolean staying = mLeaveOpenOnKeyguardHide;
        setBarState(StatusBarState.SHADE);
        View viewToClick = null;
        if (mLeaveOpenOnKeyguardHide) {
            mLeaveOpenOnKeyguardHide = false;
            long delay = calculateGoingToFullShadeDelay();
            mNotificationPanel.animateToFullShade(delay);
            if (mDraggedDownRow != null) {
                mDraggedDownRow.setUserLocked(false);
                mDraggedDownRow = null;
            }
            viewToClick = mPendingRemoteInputView;
            mPendingRemoteInputView = null;

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            if (mNavigationBarView != null) {
                mNavigationBarView.setLayoutTransitionsEnabled(false);
                mNavigationBarView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mNavigationBarView.setLayoutTransitionsEnabled(true);
                    }
                }, delay + StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE);
            }
        } else {
            instantCollapseNotificationPanel();
        }
        updateKeyguardState(staying, false /* fromShadeLocked */);

        if (viewToClick != null) {
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
        mKeyguardGoingAway = true;
        mIconController.appTransitionPending();
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     *
     * @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in miliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration) {
        mKeyguardFadingAway = true;
        mKeyguardFadingAwayDelay = delay;
        mKeyguardFadingAwayDuration = fadeoutDuration;
        mWaitingForKeyguardExit = false;
        mIconController.appTransitionStarting(
                startTime + fadeoutDuration
                        - StatusBarIconController.DEFAULT_TINT_ANIMATION_DURATION,
                StatusBarIconController.DEFAULT_TINT_ANIMATION_DURATION);
        disable(mDisabledUnmodified1, mDisabledUnmodified2, fadeoutDuration > 0 /* animate */);
    }

    public boolean isKeyguardFadingAway() {
        return mKeyguardFadingAway;
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    public void finishKeyguardFadingAway() {
        mKeyguardFadingAway = false;
        mKeyguardGoingAway = false;
    }

    public void stopWaitingForKeyguardExit() {
        mWaitingForKeyguardExit = false;
    }

    private void updatePublicMode() {
        boolean isPublic = false;
        if (mStatusBarKeyguardViewManager.isShowing()) {
            for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
                UserInfo userInfo = mCurrentProfiles.valueAt(i);
                if (mStatusBarKeyguardViewManager.isSecure(userInfo.id)) {
                    isPublic = true;
                    break;
                }
            }
        }
        setLockscreenPublicMode(isPublic);
    }

    protected void updateKeyguardState(boolean goingToFullShade, boolean fromShadeLocked) {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardIndicationController.setVisible(true);
            mNotificationPanel.resetViews();
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(true, fromShadeLocked);
            }
            mStatusBarView.removePendingHideExpandedRunnables();
        } else {
            mKeyguardIndicationController.setVisible(false);
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.setKeyguard(false,
                        goingToFullShade ||
                        mState == StatusBarState.SHADE_LOCKED ||
                        fromShadeLocked);
            }
        }
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            mScrimController.setKeyguardShowing(true);
        } else {
            mScrimController.setKeyguardShowing(false);
        }
        mIconPolicy.notifyKeyguardShowingChanged();
        mNotificationPanel.setBarState(mState, mKeyguardFadingAway, goingToFullShade);
        updateDozingState();
        updatePublicMode();
        updateStackScrollerState(goingToFullShade, fromShadeLocked);
        updateNotifications();
        checkBarModes();
        updateMediaMetaData(false, mState != StatusBarState.KEYGUARD);
        mKeyguardMonitor.notifyKeyguardState(mStatusBarKeyguardViewManager.isShowing(),
                mStatusBarKeyguardViewManager.isSecure());
    }

    private void updateDozingState() {
        boolean animate = !mDozing && mDozeScrimController.isPulsing();
        mNotificationPanel.setDozing(mDozing, animate);
        mStackScroller.setDark(mDozing, animate, mWakeUpTouchLocation);
        mScrimController.setDozing(mDozing);

        // Immediately abort the dozing from the doze scrim controller in case of wake-and-unlock
        // for pulsing so the Keyguard fade-out animation scrim can take over.
        mDozeScrimController.setDozing(mDozing &&
                mFingerprintUnlockController.getMode()
                        != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING, animate);
    }

    public void updateStackScrollerState(boolean goingToFullShade, boolean fromShadeLocked) {
        if (mStackScroller == null) return;
        boolean onKeyguard = mState == StatusBarState.KEYGUARD;
        mStackScroller.setHideSensitive(isLockscreenPublicMode(), goingToFullShade);
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

    public boolean onMenuPressed() {
        if (mDeviceInteractive && mState != StatusBarState.SHADE
                && mStatusBarKeyguardViewManager.shouldDismissOnMenuPressed()) {
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
        if (mStatusBarKeyguardViewManager.onBackPressed()) {
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

    private void showBouncer() {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            mWaitingForKeyguardExit = mStatusBarKeyguardViewManager.isShowing();
            mStatusBarKeyguardViewManager.dismiss();
        }
    }

    private void instantExpandNotificationsPanel() {

        // Make our window larger and the panel expanded.
        makeExpandedVisible(true);
        mNotificationPanel.expand(false /* animate */);
    }

    private void instantCollapseNotificationPanel() {
        mNotificationPanel.instantCollapse();
    }

    @Override
    public void onActivated(ActivatableNotificationView view) {
        EventLogTags.writeSysuiLockscreenGesture(
                EventLogConstants.SYSUI_LOCKSCREEN_GESTURE_TAP_NOTIFICATION_ACTIVATE,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        mKeyguardIndicationController.showTransientIndication(R.string.notification_tap_again);
        ActivatableNotificationView previousView = mStackScroller.getActivatedChild();
        if (previousView != null) {
            previousView.makeInactive(true /* animate */);
        }
        mStackScroller.setActivatedChild(view);
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
            removeRemoteInputEntriesKeptUntilCollapsed();
        }
        mState = state;
        mGroupManager.setStatusBarState(state);
        mFalsingManager.setStatusBarState(state);
        mStatusBarWindowManager.setStatusBarState(state);
        updateDozing();
    }

    @Override
    public void onActivationReset(ActivatableNotificationView view) {
        if (view == mStackScroller.getActivatedChild()) {
            mKeyguardIndicationController.hideTransientIndication();
            mStackScroller.setActivatedChild(null);
        }
    }

    public void onTrackingStarted() {
        runPostCollapseRunnables();
    }

    public void onClosingFinished() {
        runPostCollapseRunnables();
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
                showBouncer();
            }
        }
    }

    @Override
    protected int getMaxKeyguardNotifications(boolean recompute) {
        if (recompute) {
            mMaxKeyguardNotifications = Math.max(1,
                    mNotificationPanel.computeMaxKeyguardNotifications(
                            mMaxAllowedKeyguardNotifications));
            return mMaxKeyguardNotifications;
        }
        return mMaxKeyguardNotifications;
    }

    public int getMaxKeyguardNotifications() {
        return getMaxKeyguardNotifications(false /* recompute */);
    }

    public NavigationBarView getNavigationBarView() {
        return mNavigationBarView;
    }

    // ---------------------- DragDownHelper.OnDragDownListener ------------------------------------


    /* Only ever called as a consequence of a lockscreen expansion gesture. */
    @Override
    public boolean onDraggedDown(View startingChild, int dragLengthY) {
        if (hasActiveNotifications()) {
            EventLogTags.writeSysuiLockscreenGesture(
                    EventLogConstants.SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_FULL_SHADE,
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

            // No notifications - abort gesture.
            return false;
        }
    }

    @Override
    public void onDragDownReset() {
        mStackScroller.setDimmed(true /* dimmed */, true /* animated */);
        mStackScroller.resetScrollPosition();
    }

    @Override
    public void onCrossedThreshold(boolean above) {
        mStackScroller.setDimmed(!above /* dimmed */, true /* animate */);
    }

    @Override
    public void onTouchSlopExceeded() {
        mStackScroller.removeLongPressCallback();
    }

    @Override
    public void setEmptyDragAmount(float amount) {
        mNotificationPanel.setEmptyDragAmount(amount);
    }

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * <p>If secure without redaction or no security: Go to {@link StatusBarState#SHADE_LOCKED}.</p>
     *
     * @param expandView The view to expand after going to the shade.
     */
    public void goToLockedShade(View expandView) {
        ExpandableNotificationRow row = null;
        if (expandView instanceof ExpandableNotificationRow) {
            row = (ExpandableNotificationRow) expandView;
            row.setUserExpanded(true /* userExpanded */, true /* allowChildExpansion */);
            // Indicate that the group expansion is changing at this time -- this way the group
            // and children backgrounds / divider animations will look correct.
            row.setGroupExpansionChanging(true);
        }
        boolean fullShadeNeedsBouncer = !userAllowsPrivateNotificationsInPublic(mCurrentUserId)
                || !mShowLockscreenNotifications || mFalsingManager.shouldEnforceBouncer();
        if (isLockscreenPublicMode() && fullShadeNeedsBouncer) {
            mLeaveOpenOnKeyguardHide = true;
            showBouncer();
            mDraggedDownRow = row;
            mPendingRemoteInputView = null;
        } else {
            mNotificationPanel.animateToFullShade(0 /* delay */);
            setBarState(StatusBarState.SHADE_LOCKED);
            updateKeyguardState(false /* goingToFullShade */, false /* fromShadeLocked */);
        }
    }

    @Override
    public void onLockedNotificationImportanceChange(OnDismissAction dismissAction) {
        mLeaveOpenOnKeyguardHide = true;
        dismissKeyguardThenExecute(dismissAction, true /* afterKeyguardGone */);
    }

    @Override
    protected void onLockedRemoteInput(ExpandableNotificationRow row, View clicked) {
        mLeaveOpenOnKeyguardHide = true;
        showBouncer();
        mPendingRemoteInputView = clicked;
    }

    @Override
    protected boolean startWorkChallengeIfNecessary(int userId, IntentSender intendSender,
            String notificationKey) {
        // Clear pending remote view, as we do not want to trigger pending remote input view when
        // it's called by other code
        mPendingWorkRemoteInputView = null;
        return super.startWorkChallengeIfNecessary(userId, intendSender, notificationKey);
    }

    @Override
    protected void onLockedWorkRemoteInput(int userId, ExpandableNotificationRow row,
            View clicked) {
        // Collapse notification and show work challenge
        animateCollapsePanels();
        startWorkChallengeIfNecessary(userId, null, null);
        // Add pending remote input view after starting work challenge, as starting work challenge
        // will clear all previous pending review view
        mPendingWorkRemoteInputView = clicked;
    }

    @Override
    protected void onWorkChallengeUnlocked() {
        if (mPendingWorkRemoteInputView != null) {
            final View pendingWorkRemoteInputView = mPendingWorkRemoteInputView;
            // Expand notification panel and the notification row, then click on remote input view
            final Runnable clickPendingViewRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mPendingWorkRemoteInputView != null) {
                        final View pendingWorkRemoteInputView = mPendingWorkRemoteInputView;
                        ViewParent p = pendingWorkRemoteInputView.getParent();
                        while (p != null) {
                            if (p instanceof ExpandableNotificationRow) {
                                final ExpandableNotificationRow row = (ExpandableNotificationRow) p;
                                ViewParent viewParent = row.getParent();
                                if (viewParent instanceof NotificationStackScrollLayout) {
                                    final NotificationStackScrollLayout scrollLayout =
                                            (NotificationStackScrollLayout) viewParent;
                                    row.makeActionsVisibile();
                                    row.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            final Runnable finishScrollingCallback = new Runnable()
                                            {
                                                @Override
                                                public void run() {
                                                    mPendingWorkRemoteInputView.callOnClick();
                                                    mPendingWorkRemoteInputView = null;
                                                    scrollLayout.setFinishScrollingCallback(null);
                                                }
                                            };
                                            if (scrollLayout.scrollTo(row)) {
                                                // It scrolls! So call it when it's finished.
                                                scrollLayout.setFinishScrollingCallback(
                                                        finishScrollingCallback);
                                            } else {
                                                // It does not scroll, so call it now!
                                                finishScrollingCallback.run();
                                            }
                                        }
                                    });
                                }
                                break;
                            }
                            p = p.getParent();
                        }
                    }
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

    @Override
    public void setBouncerShowing(boolean bouncerShowing) {
        super.setBouncerShowing(bouncerShowing);
        mStatusBarView.setBouncerShowing(bouncerShowing);
        disable(mDisabledUnmodified1, mDisabledUnmodified2, true /* animate */);
    }

    public void onStartedGoingToSleep() {
        mStartedGoingToSleep = true;
    }

    public void onFinishedGoingToSleep() {
        mNotificationPanel.onAffordanceLaunchEnded();
        releaseGestureWakeLock();
        mLaunchCameraOnScreenTurningOn = false;
        mStartedGoingToSleep = false;
        mDeviceInteractive = false;
        mWakeUpComingFromTouch = false;
        mWakeUpTouchLocation = null;
        mStackScroller.setAnimationsEnabled(false);
        updateVisibleToUser();
        if (mLaunchCameraOnFinishedGoingToSleep) {
            mLaunchCameraOnFinishedGoingToSleep = false;

            // This gets executed before we will show Keyguard, so post it in order that the state
            // is correct.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onCameraLaunchGestureDetected(mLastCameraLaunchSource);
                }
            });
        }
    }

    public void onStartedWakingUp() {
        mDeviceInteractive = true;
        mStackScroller.setAnimationsEnabled(true);
        mNotificationPanel.setTouchDisabled(false);
        updateVisibleToUser();
    }

    public void onScreenTurningOn() {
        mScreenTurningOn = true;
        mFalsingManager.onScreenTurningOn();
        mNotificationPanel.onScreenTurningOn();
        if (mLaunchCameraOnScreenTurningOn) {
            mNotificationPanel.launchCamera(false, mLastCameraLaunchSource);
            mLaunchCameraOnScreenTurningOn = false;
        }
    }

    private void vibrateForCameraGesture() {
        // Make sure to pass -1 for repeat so VibratorService doesn't stop us when going to sleep.
        mVibrator.vibrate(new long[]{0, 750L}, -1 /* repeat */);
    }

    public void onScreenTurnedOn() {
        mScreenTurningOn = false;
        mDozeScrimController.onScreenTurnedOn();
    }

    /**
     * Handles long press for back button. This exits screen pinning.
     */
    private boolean handleLongPressBack() {
        try {
            IActivityManager activityManager = ActivityManagerNative.getDefault();
            if (activityManager.isInLockTaskMode()) {
                activityManager.stopSystemLockTaskMode();

                // When exiting refresh disabled flags.
                mNavigationBarView.setDisabledFlags(mDisabled1, true);
                return true;
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Unable to reach activity manager", e);
        }
        return false;
    }

    public void updateRecentsVisibility(boolean visible) {
        // Update the recents visibility flag
        if (visible) {
            mSystemUiVisibility |= View.RECENT_APPS_VISIBLE;
        } else {
            mSystemUiVisibility &= ~View.RECENT_APPS_VISIBLE;
        }
        notifyUiVisibilityChanged(mSystemUiVisibility);
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
        return !mNotificationData.getActiveNotifications().isEmpty();
    }

    public void wakeUpIfDozing(long time, MotionEvent event) {
        if (mDozing && mDozeScrimController.isPulsing()) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            pm.wakeUp(time, "com.android.systemui:NODOZE");
            mWakeUpComingFromTouch = true;
            mWakeUpTouchLocation = new PointF(event.getX(), event.getY());
            mNotificationPanel.setTouchDisabled(false);
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
            mFalsingManager.onScreenOnFromTouch();
        }
    }

    @Override
    public void appTransitionPending() {

        // Use own timings when Keyguard is going away, see keyguardGoingAway and
        // setKeyguardFadingAway
        if (!mKeyguardFadingAway) {
            mIconController.appTransitionPending();
        }
    }

    @Override
    public void appTransitionCancelled() {
        mIconController.appTransitionCancelled();
        EventBus.getDefault().send(new AppTransitionFinishedEvent());
    }

    @Override
    public void appTransitionStarting(long startTime, long duration) {

        // Use own timings when Keyguard is going away, see keyguardGoingAway and
        // setKeyguardFadingAway.
        if (!mKeyguardGoingAway) {
            mIconController.appTransitionStarting(startTime, duration);
        }
        if (mIconPolicy != null) {
            mIconPolicy.appTransitionStarting(startTime, duration);
        }
    }

    @Override
    public void appTransitionFinished() {
        EventBus.getDefault().send(new AppTransitionFinishedEvent());
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mLastCameraLaunchSource = source;
        if (mStartedGoingToSleep) {
            mLaunchCameraOnFinishedGoingToSleep = true;
            return;
        }
        if (!mNotificationPanel.canCameraGestureBeLaunched(
                mStatusBarKeyguardViewManager.isShowing() && mExpandedVisible)) {
            return;
        }
        if (!mDeviceInteractive) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            pm.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:CAMERA_GESTURE");
            mStatusBarKeyguardViewManager.notifyDeviceWakeUpRequested();
        }
        vibrateForCameraGesture();
        if (!mStatusBarKeyguardViewManager.isShowing()) {
            startActivity(KeyguardBottomAreaView.INSECURE_CAMERA_INTENT,
                    true /* dismissShade */);
        } else {
            if (!mDeviceInteractive) {
                // Avoid flickering of the scrim when we instant launch the camera and the bouncer
                // comes on.
                mScrimController.dontAnimateBouncerChangesUntilNextFrame();
                mGestureWakeLock.acquire(LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
            }
            if (mScreenTurningOn || mStatusBarKeyguardViewManager.isScreenTurnedOn()) {
                mNotificationPanel.launchCamera(mDeviceInteractive /* animate */, source);
            } else {
                // We need to defer the camera launch until the screen comes on, since otherwise
                // we will dismiss us too early since we are waiting on an activity to be drawn and
                // incorrectly get notified because of the screen on event (which resumes and pauses
                // some activities)
                mLaunchCameraOnScreenTurningOn = true;
            }
        }
    }

    @Override
    public void showTvPictureInPictureMenu() {
        // no-op.
    }

    public void notifyFpAuthModeChanged() {
        updateDozing();
    }

    private void updateDozing() {
        // When in wake-and-unlock while pulsing, keep dozing state until fully unlocked.
        mDozing = mDozingRequested && mState == StatusBarState.KEYGUARD
                || mFingerprintUnlockController.getMode()
                        == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING;
        updateDozingState();
    }

    private final class ShadeUpdates {
        private final ArraySet<String> mVisibleNotifications = new ArraySet<String>();
        private final ArraySet<String> mNewVisibleNotifications = new ArraySet<String>();

        public void check() {
            mNewVisibleNotifications.clear();
            ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
            for (int i = 0; i < activeNotifications.size(); i++) {
                final Entry entry = activeNotifications.get(i);
                final boolean visible = entry.row != null
                        && entry.row.getVisibility() == View.VISIBLE;
                if (visible) {
                    mNewVisibleNotifications.add(entry.key + entry.notification.getPostTime());
                }
            }
            final boolean updates = !mVisibleNotifications.containsAll(mNewVisibleNotifications);
            mVisibleNotifications.clear();
            mVisibleNotifications.addAll(mNewVisibleNotifications);

            // We have new notifications
            if (updates && mDozeServiceHost != null) {
                mDozeServiceHost.fireNewNotifications();
            }
        }
    }

    private final class DozeServiceHost extends KeyguardUpdateMonitorCallback implements DozeHost  {
        // Amount of time to allow to update the time shown on the screen before releasing
        // the wakelock.  This timeout is design to compensate for the fact that we don't
        // currently have a way to know when time display contents have actually been
        // refreshed once we've finished rendering a new frame.
        private static final long PROCESSING_TIME = 500;

        private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
        private final H mHandler = new H();

        // Keeps the last reported state by fireNotificationLight.
        private boolean mNotificationLightOn;

        @Override
        public String toString() {
            return "PSB.DozeServiceHost[mCallbacks=" + mCallbacks.size() + "]";
        }

        public void firePowerSaveChanged(boolean active) {
            for (Callback callback : mCallbacks) {
                callback.onPowerSaveChanged(active);
            }
        }

        public void fireBuzzBeepBlinked() {
            for (Callback callback : mCallbacks) {
                callback.onBuzzBeepBlinked();
            }
        }

        public void fireNotificationLight(boolean on) {
            mNotificationLightOn = on;
            for (Callback callback : mCallbacks) {
                callback.onNotificationLight(on);
            }
        }

        public void fireNewNotifications() {
            for (Callback callback : mCallbacks) {
                callback.onNewNotifications();
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
        public void startDozing(@NonNull Runnable ready) {
            mHandler.obtainMessage(H.MSG_START_DOZING, ready).sendToTarget();
        }

        @Override
        public void pulseWhileDozing(@NonNull PulseCallback callback, int reason) {
            mHandler.obtainMessage(H.MSG_PULSE_WHILE_DOZING, reason, 0, callback).sendToTarget();
        }

        @Override
        public void stopDozing() {
            mHandler.obtainMessage(H.MSG_STOP_DOZING).sendToTarget();
        }

        @Override
        public boolean isPowerSaveActive() {
            return mBatteryController != null && mBatteryController.isPowerSave();
        }

        @Override
        public boolean isPulsingBlocked() {
            return mFingerprintUnlockController.getMode()
                    == FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;
        }

        @Override
        public boolean isNotificationLightOn() {
            return mNotificationLightOn;
        }

        private void handleStartDozing(@NonNull Runnable ready) {
            if (!mDozingRequested) {
                mDozingRequested = true;
                DozeLog.traceDozing(mContext, mDozing);
                updateDozing();
            }
            ready.run();
        }

        private void handlePulseWhileDozing(@NonNull PulseCallback callback, int reason) {
            mDozeScrimController.pulse(new PulseCallback() {

                @Override
                public void onPulseStarted() {
                    callback.onPulseStarted();
                    mStackScroller.setPulsing(true);
                }

                @Override
                public void onPulseFinished() {
                    callback.onPulseFinished();
                    mStackScroller.setPulsing(false);
                }
            }, reason);
        }

        private void handleStopDozing() {
            if (mDozingRequested) {
                mDozingRequested = false;
                DozeLog.traceDozing(mContext, mDozing);
                updateDozing();
            }
        }

        private final class H extends Handler {
            private static final int MSG_START_DOZING = 1;
            private static final int MSG_PULSE_WHILE_DOZING = 2;
            private static final int MSG_STOP_DOZING = 3;

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_START_DOZING:
                        handleStartDozing((Runnable) msg.obj);
                        break;
                    case MSG_PULSE_WHILE_DOZING:
                        handlePulseWhileDozing((PulseCallback) msg.obj, msg.arg1);
                        break;
                    case MSG_STOP_DOZING:
                        handleStopDozing();
                        break;
                }
            }
        }
    }
}
