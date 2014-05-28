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


import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.windowStateToString;
import static com.android.keyguard.KeyguardHostView.OnDismissAction;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.DemoMode;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.qs.CircularClipper;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.InterceptedNotifications;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.NotificationOverflowContainer;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.SpeedBumpView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.HeadsUpNotificationView;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.LocationControllerImpl;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout.OnChildLocationsChangedListener;
import com.android.systemui.statusbar.stack.StackScrollState.ViewState;
import com.android.systemui.volume.VolumeComponent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class PhoneStatusBar extends BaseStatusBar implements DemoMode,
        DragDownHelper.OnDragDownListener, ActivityStarter {
    static final String TAG = "PhoneStatusBar";
    public static final boolean DEBUG = BaseStatusBar.DEBUG;
    public static final boolean SPEW = false;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info
    public static final boolean DEBUG_GESTURES = false;

    public static final boolean DEBUG_WINDOW_STATE = false;

    public static final boolean SETTINGS_DRAG_SHORTCUT = true;

    // additional instrumentation for testing purposes; intended to be left on during development
    public static final boolean CHATTY = DEBUG;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    private static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
    private static final int MSG_CLOSE_PANELS = 1001;
    private static final int MSG_OPEN_SETTINGS_PANEL = 1002;
    // 1020-1030 reserved for BaseStatusBar

    private static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    private static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10; // see NotificationManagerService
    private static final int HIDE_ICONS_BELOW_SCORE = Notification.PRIORITY_LOW * NOTIFICATION_PRIORITY_MULTIPLIER;

    /**
     * Default value of {@link android.provider.Settings.Global#LOCK_SCREEN_SHOW_NOTIFICATIONS}.
     */
    private static final boolean ALLOW_NOTIFICATIONS_DEFAULT = false;

    private static final int STATUS_OR_NAV_TRANSIENT =
            View.STATUS_BAR_TRANSIENT | View.NAVIGATION_BAR_TRANSIENT;
    private static final long AUTOHIDE_TIMEOUT_MS = 3000;

    /** The minimum delay in ms between reports of notification visibility. */
    private static final int VISIBILITY_REPORT_MIN_DELAY_MS = 500;

    // fling gesture tuning parameters, scaled to display density
    private float mSelfExpandVelocityPx; // classic value: 2000px/s
    private float mSelfCollapseVelocityPx; // classic value: 2000px/s (will be negated to collapse "up")
    private float mFlingExpandMinVelocityPx; // classic value: 200px/s
    private float mFlingCollapseMinVelocityPx; // classic value: 200px/s
    private float mCollapseMinDisplayFraction; // classic value: 0.08 (25px/min(320px,480px) on G1)
    private float mExpandMinDisplayFraction; // classic value: 0.5 (drag open halfway to expand)
    private float mFlingGestureMaxXVelocityPx; // classic value: 150px/s

    private float mExpandAccelPx; // classic value: 2000px/s/s
    private float mCollapseAccelPx; // classic value: 2000px/s/s (will be negated to collapse "up")

    private float mFlingGestureMaxOutputVelocityPx; // how fast can it really go? (should be a little
                                                    // faster than mSelfCollapseVelocityPx)

    PhoneStatusBarPolicy mIconPolicy;

    // These are no longer handled by the policy, because we need custom strategies for them
    BluetoothControllerImpl mBluetoothController;
    BatteryController mBatteryController;
    LocationControllerImpl mLocationController;
    NetworkControllerImpl mNetworkController;
    RotationLockControllerImpl mRotationLockController;
    UserInfoController mUserInfoController;
    ZenModeController mZenModeController;
    CastControllerImpl mCastController;
    VolumeComponent mVolumeComponent;

    int mNaturalBarHeight = -1;
    int mIconSize = -1;
    int mIconHPadding = -1;
    Display mDisplay;
    Point mCurrentDisplaySize = new Point();

    StatusBarWindowView mStatusBarWindow;
    PhoneStatusBarView mStatusBarView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    private StatusBarWindowManager mStatusBarWindowManager;
    private UnlockMethodCache mUnlockMethodCache;

    int mPixelFormat;
    Object mQueueLock = new Object();

    // viewgroup containing the normal contents of the statusbar
    LinearLayout mStatusBarContents;

    // right-hand icons
    LinearLayout mSystemIconArea;
    LinearLayout mSystemIcons;

    // left-hand icons
    LinearLayout mStatusIcons;
    // the icons themselves
    IconMerger mNotificationIcons;
    // [+>
    View mMoreIcon;

    // expanded notifications
    NotificationPanelView mNotificationPanel; // the sliding/resizing panel within the notification window
    View mExpandedContents;
    int mNotificationPanelGravity;
    int mNotificationPanelMarginBottomPx;
    float mNotificationPanelMinHeightFrac;
    TextView mNotificationPanelDebugText;

    // settings
    View mFlipSettingsView;
    private QSPanel mQSPanel;

    // top bar
    StatusBarHeaderView mHeader;
    View mKeyguardStatusView;
    KeyguardBottomAreaView mKeyguardBottomArea;
    boolean mLeaveOpenOnKeyguardHide;
    KeyguardIndicationTextView mKeyguardIndicationTextView;

    // TODO: Fetch phrase from search/hotword provider.
    String mKeyguardHotwordPhrase = "";
    int mKeyguardMaxNotificationCount;
    View mDateTimeView;

    // carrier/wifi label
    private TextView mCarrierLabel;
    private boolean mCarrierLabelVisible = false;
    private int mCarrierLabelHeight;
    private TextView mEmergencyCallLabel;
    private int mStatusBarHeaderHeight;

    private boolean mShowCarrierInPanel = false;

    // position
    int[] mPositionTmp = new int[2];
    boolean mExpandedVisible;

    // the date view
    DateView mDateView;

    // for heads up notifications
    private HeadsUpNotificationView mHeadsUpNotificationView;
    private int mHeadsUpNotificationDecay;

    // on-screen navigation buttons
    private NavigationBarView mNavigationBarView = null;
    private int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    // the tracker view
    int mTrackingPosition; // the position of the top of the tracking view.

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;

    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;

    int[] mAbsPos = new int[2];
    Runnable mPostCollapseCleanup = null;

    // for disabling the status bar
    int mDisabled = 0;

    // tracking calls to View.setSystemUiVisibility()
    int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;

    DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private int mNavigationIconHints = 0;
    private final Animator.AnimatorListener mMakeIconsInvisible = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            // double-check to avoid races
            if (mStatusBarContents.getAlpha() == 0) {
                if (DEBUG) Log.d(TAG, "makeIconsInvisible");
                mStatusBarContents.setVisibility(View.INVISIBLE);
            }
        }
    };

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
                if (mNotificationPanel != null) {
                    mNotificationPanel.setQsExpansionEnabled(isDeviceProvisioned() && userSetup);
                }
                if (!mUserSetup && mStatusBarView != null)
                    animateCollapseQuickSettings();
            }
        }
    };

    final private ContentObserver mHeadsUpObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            boolean wasUsing = mUseHeadsUp;
            mUseHeadsUp = ENABLE_HEADS_UP && Settings.Global.HEADS_UP_OFF != Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    Settings.Global.HEADS_UP_OFF);
            mHeadsUpTicker = mUseHeadsUp && 0 != Settings.Global.getInt(
                    mContext.getContentResolver(), SETTING_HEADS_UP_TICKER, 0);
            Log.d(TAG, "heads up is " + (mUseHeadsUp ? "enabled" : "disabled"));
            if (wasUsing != mUseHeadsUp) {
                if (!mUseHeadsUp) {
                    Log.d(TAG, "dismissing any existing heads up notification on disable event");
                    setHeadsUpVisibility(false);
                    mHeadsUpNotificationView.setNotification(null);
                    removeHeadsUpView();
                } else {
                    addHeadsUpView();
                }
            }
        }
    };

    private int mInteractingWindows;
    private boolean mAutohideSuspended;
    private int mStatusBarMode;
    private int mNavigationBarMode;
    private Boolean mScreenOn;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private ViewMediatorCallback mKeyguardViewMediatorCallback;
    private ScrimController mScrimController;

    private final Runnable mAutohide = new Runnable() {
        @Override
        public void run() {
            int requested = mSystemUiVisibility & ~STATUS_OR_NAV_TRANSIENT;
            if (mSystemUiVisibility != requested) {
                notifyUiVisibilityChanged(requested);
            }
        }};

    private Runnable mOnFlipRunnable;
    private InterceptedNotifications mIntercepted;
    private VelocityTracker mSettingsTracker;
    private float mSettingsDownY;
    private boolean mSettingsStarted;
    private boolean mSettingsCancelled;
    private boolean mSettingsClosing;
    private boolean mVisible;

    private final OnChildLocationsChangedListener mOnChildLocationsChangedListener =
            new OnChildLocationsChangedListener() {
        @Override
        public void onChildLocationsChanged(NotificationStackScrollLayout stackScrollLayout) {
            userActivity();
        }
    };

    public void setOnFlipRunnable(Runnable onFlipRunnable) {
        mOnFlipRunnable = onFlipRunnable;
    }

    /** Keys of notifications currently visible to the user. */
    private final ArraySet<String> mCurrentlyVisibleNotifications = new ArraySet<String>();
    private long mLastVisibilityReportUptimeMs;

    private static final int VISIBLE_LOCATIONS = ViewState.LOCATION_FIRST_CARD
            | ViewState.LOCATION_TOP_STACK_PEEKING
            | ViewState.LOCATION_MAIN_AREA
            | ViewState.LOCATION_BOTTOM_STACK_PEEKING;

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
        private final ArrayList<String> mTmpNewlyVisibleNotifications = new ArrayList<String>();
        private final ArrayList<String> mTmpCurrentlyVisibleNotifications = new ArrayList<String>();

        @Override
        public void run() {
            mLastVisibilityReportUptimeMs = SystemClock.uptimeMillis();

            // 1. Loop over mNotificationData entries:
            //   A. Keep list of visible notifications.
            //   B. Keep list of previously hidden, now visible notifications.
            // 2. Compute no-longer visible notifications by removing currently
            //    visible notifications from the set of previously visible
            //    notifications.
            // 3. Report newly visible and no-longer visible notifications.
            // 4. Keep currently visible notifications for next report.
            int N = mNotificationData.size();
            for (int i = 0; i < N; i++) {
                Entry entry = mNotificationData.get(i);
                String key = entry.notification.getKey();
                boolean previouslyVisible = mCurrentlyVisibleNotifications.contains(key);
                boolean currentlyVisible =
                        (mStackScroller.getChildLocation(entry.row) & VISIBLE_LOCATIONS) != 0;
                if (currentlyVisible) {
                    // Build new set of visible notifications.
                    mTmpCurrentlyVisibleNotifications.add(key);
                }
                if (!previouslyVisible && currentlyVisible) {
                    mTmpNewlyVisibleNotifications.add(key);
                }
            }
            ArraySet<String> noLongerVisibleNotifications = mCurrentlyVisibleNotifications;
            noLongerVisibleNotifications.removeAll(mTmpCurrentlyVisibleNotifications);

            logNotificationVisibilityChanges(
                    mTmpNewlyVisibleNotifications, noLongerVisibleNotifications);

            mCurrentlyVisibleNotifications.clear();
            mCurrentlyVisibleNotifications.addAll(mTmpCurrentlyVisibleNotifications);

            mTmpNewlyVisibleNotifications.clear();
            mTmpCurrentlyVisibleNotifications.clear();
        }
    };

    private final View.OnClickListener mOverflowClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            goToLockedShade(null);
        }
    };

    @Override
    public void setZenMode(int mode) {
        super.setZenMode(mode);
        if (!isDeviceProvisioned()) return;
        final boolean zen = mode != Settings.Global.ZEN_MODE_OFF;
        if (!zen) {
            mIntercepted.releaseIntercepted();
        }
        if (mIconPolicy != null) {
            mIconPolicy.setZenMode(zen);
        }
    }

    @Override
    protected void setShowLockscreenNotifications(boolean show) {
        super.setShowLockscreenNotifications(show);
        updateStackScrollerState();
    }

    @Override
    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        updateDisplaySize();
        mIntercepted = new InterceptedNotifications(mContext, this);
        super.start(); // calls createAndAddWindows()

        addNavigationBar();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext);
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
        startKeyguard();
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected PhoneStatusBarView makeStatusBarView() {
        final Context context = mContext;

        Resources res = context.getResources();

        updateDisplaySize(); // populates mDisplayMetrics
        loadDimens();

        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

        mStatusBarWindow = (StatusBarWindowView) View.inflate(context,
                R.layout.super_status_bar, null);
        mStatusBarWindow.mService = this;
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
            }});

        mStatusBarView = (PhoneStatusBarView) mStatusBarWindow.findViewById(R.id.status_bar);
        mStatusBarView.setBar(this);

        PanelHolder holder = (PanelHolder) mStatusBarWindow.findViewById(R.id.panel_holder);
        mStatusBarView.setPanelHolder(holder);

        mNotificationPanel = (NotificationPanelView) mStatusBarWindow.findViewById(
                R.id.notification_panel);
        mNotificationPanel.setStatusBar(this);

        // make the header non-responsive to clicks
        mNotificationPanel.findViewById(R.id.header).setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return true; // e eats everything
                    }
                });

        if (!ActivityManager.isHighEndGfx()) {
            mStatusBarWindow.setBackground(null);
            mNotificationPanel.setBackground(new FastColorDrawable(context.getResources().getColor(
                    R.color.notification_panel_solid_background)));
        }
        if (ENABLE_HEADS_UP) {
            mHeadsUpNotificationView =
                    (HeadsUpNotificationView) View.inflate(context, R.layout.heads_up, null);
            mHeadsUpNotificationView.setVisibility(View.GONE);
            mHeadsUpNotificationView.setBar(this);
        }
        if (MULTIUSER_DEBUG) {
            mNotificationPanelDebugText = (TextView) mNotificationPanel.findViewById(
                    R.id.header_debug_info);
            mNotificationPanelDebugText.setVisibility(View.VISIBLE);
        }

        updateShowSearchHoldoff();

        try {
            boolean showNav = mWindowManagerService.hasNavigationBar();
            if (DEBUG) Log.v(TAG, "hasNavigationBar=" + showNav);
            if (showNav) {
                mNavigationBarView =
                    (NavigationBarView) View.inflate(context, R.layout.navigation_bar, null);

                mNavigationBarView.setDisabledFlags(mDisabled);
                mNavigationBarView.setBar(this);
                mNavigationBarView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        checkUserAutohide(v, event);
                        return false;
                    }});
            }
        } catch (RemoteException ex) {
            // no window manager? good luck with that
        }

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.OPAQUE;

        mSystemIconArea = (LinearLayout) mStatusBarView.findViewById(R.id.system_icon_area);
        mSystemIcons = (LinearLayout) mStatusBarView.findViewById(R.id.system_icons);
        mStatusIcons = (LinearLayout)mStatusBarView.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)mStatusBarView.findViewById(R.id.notificationIcons);
        mMoreIcon = mStatusBarView.findViewById(R.id.moreIcon);
        mNotificationIcons.setOverflowIndicator(mMoreIcon);
        mStatusBarContents = (LinearLayout)mStatusBarView.findViewById(R.id.status_bar_contents);
        mTickerView = mStatusBarView.findViewById(R.id.ticker);

        mStackScroller = (NotificationStackScrollLayout) mStatusBarWindow.findViewById(
                R.id.notification_stack_scroller);
        mStackScroller.setLongPressListener(getNotificationLongClicker());
        mStackScroller.setChildLocationsChangedListener(mOnChildLocationsChangedListener);

        mKeyguardIconOverflowContainer =
                (NotificationOverflowContainer) LayoutInflater.from(mContext).inflate(
                        R.layout.status_bar_notification_keyguard_overflow, mStackScroller, false);
        mKeyguardIconOverflowContainer.setOnActivatedListener(this);
        mKeyguardIconOverflowContainer.setOnClickListener(mOverflowClickListener);
        mStackScroller.addView(mKeyguardIconOverflowContainer);

        SpeedBumpView speedBump = (SpeedBumpView) LayoutInflater.from(mContext).inflate(
                        R.layout.status_bar_notification_speed_bump, mStackScroller, false);
        mStackScroller.setSpeedBumpView(speedBump);
        mExpandedContents = mStackScroller;

        mScrimController = new ScrimController(mStatusBarWindow.findViewById(R.id.scrim_behind),
                mStatusBarWindow.findViewById(R.id.scrim_in_front));
        mStatusBarView.setScrimController(mScrimController);

        mHeader = (StatusBarHeaderView) mStatusBarWindow.findViewById(R.id.header);
        mHeader.setActivityStarter(this);
        mKeyguardStatusView = mStatusBarWindow.findViewById(R.id.keyguard_status_view);
        mKeyguardBottomArea =
                (KeyguardBottomAreaView) mStatusBarWindow.findViewById(R.id.keyguard_bottom_area);
        mKeyguardBottomArea.setActivityStarter(this);
        mKeyguardIndicationTextView = (KeyguardIndicationTextView) mStatusBarWindow.findViewById(
                R.id.keyguard_indication_text);
        mDateView = (DateView)mStatusBarWindow.findViewById(R.id.date);

        mDateTimeView = mHeader.findViewById(R.id.datetime);
        if (mDateTimeView != null) {
            mDateTimeView.setOnClickListener(mClockClickListener);
            mDateTimeView.setEnabled(true);
        }

        mNotificationPanel.setSystemUiVisibility(
                View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS |
                View.STATUS_BAR_DISABLE_CLOCK);

        mTicker = new MyTicker(context, mStatusBarView);

        TickerView tickerView = (TickerView)mStatusBarView.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();

        // Other icons
        mLocationController = new LocationControllerImpl(mContext); // will post a notification
        mBatteryController = new BatteryController(mContext);
        mNetworkController = new NetworkControllerImpl(mContext);
        mBluetoothController = new BluetoothControllerImpl(mContext);
        if (mContext.getResources().getBoolean(R.bool.config_showRotationLock)) {
            mRotationLockController = new RotationLockControllerImpl(mContext);
        }
        mUserInfoController = new UserInfoController(mContext);
        mVolumeComponent = getComponent(VolumeComponent.class);
        mZenModeController = mVolumeComponent.getZenController();
        mCastController = new CastControllerImpl(mContext);
        final SignalClusterView signalCluster =
                (SignalClusterView)mStatusBarView.findViewById(R.id.signal_cluster);


        mNetworkController.addSignalCluster(signalCluster);
        signalCluster.setNetworkController(mNetworkController);

        final boolean isAPhone = mNetworkController.hasVoiceCallingFeature();
        if (isAPhone) {
            mEmergencyCallLabel =
                    (TextView) mStatusBarWindow.findViewById(R.id.emergency_calls_only);
            // TODO: Uncomment when correctly positioned
//            if (mEmergencyCallLabel != null) {
//                mNetworkController.addEmergencyLabelView(mEmergencyCallLabel);
//                mEmergencyCallLabel.setOnClickListener(new View.OnClickListener() {
//                    public void onClick(View v) { }});
//                mEmergencyCallLabel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
//                    @Override
//                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
//                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
//                        updateCarrierLabelVisibility(false);
//                    }});
//            }
        }

        mCarrierLabel = (TextView)mStatusBarWindow.findViewById(R.id.carrier_label);
        mShowCarrierInPanel = (mCarrierLabel != null);
        if (DEBUG) Log.v(TAG, "carrierlabel=" + mCarrierLabel + " show=" + mShowCarrierInPanel);
        if (mShowCarrierInPanel) {
            mCarrierLabel.setVisibility(mCarrierLabelVisible ? View.VISIBLE : View.INVISIBLE);

            // for mobile devices, we always show mobile connection info here (SPN/PLMN)
            // for other devices, we show whatever network is connected
            if (mNetworkController.hasMobileDataFeature()) {
                mNetworkController.addMobileLabelView(mCarrierLabel);
            } else {
                mNetworkController.addCombinedLabelView(mCarrierLabel);
            }

            // set up the dynamic hide/show of the label
            // TODO: uncomment, handle this for the Stack scroller aswell
//                ((NotificationRowLayout) mStackScroller)
// .setOnSizeChangedListener(new OnSizeChangedListener() {
//                @Override
//                public void onSizeChanged(View view, int w, int h, int oldw, int oldh) {
//                    updateCarrierLabelVisibility(false);
        }

        // Set up the quick settings tile panel
        mQSPanel = (QSPanel) mStatusBarWindow.findViewById(R.id.quick_settings_panel);
        if (mQSPanel != null) {
            mQSPanel.setUtils(new CircularClipper.Utils() {
                @Override
                public ValueAnimator createRevealAnimator(View v, int centerX, int centerY,
                        float startRadius, float endRadius) {
                    return v.createRevealAnimator(centerX, centerY, startRadius, endRadius);
                }
            });
            final QSTileHost qsh = new QSTileHost(mContext, this,
                    mBluetoothController, mLocationController, mRotationLockController,
                    mNetworkController, mZenModeController, null /*tethering*/,
                    mCastController, mVolumeComponent);
            for (QSTile<?> tile : qsh.getTiles()) {
                mQSPanel.addTile(tile);
            }
            mHeader.setQSPanel(mQSPanel);
        }

        // User info. Trigger first load.
        mHeader.setUserInfoController(mUserInfoController);
        mUserInfoController.reloadUserInfo();

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBroadcastReceiver.onReceive(mContext,
                new Intent(pm.isScreenOn() ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF));

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ACTION_DEMO);
        context.registerReceiver(mBroadcastReceiver, filter);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        resetUserSetupObserver();

        return mStatusBarView;
    }

    private void startKeyguard() {
        KeyguardViewMediator keyguardViewMediator = getComponent(KeyguardViewMediator.class);
        mStatusBarKeyguardViewManager = keyguardViewMediator.registerStatusBar(this,
                mStatusBarWindow, mStatusBarWindowManager, mScrimController);
        mKeyguardViewMediatorCallback = keyguardViewMediator.getViewMediatorCallback();
    }

    @Override
    protected void onShowSearchPanel() {
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().setContentVisible(false);
        }
    }

    @Override
    protected void onHideSearchPanel() {
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().setContentVisible(true);
        }
    }

    @Override
    protected View getStatusBarView() {
        return mStatusBarView;
    }

    @Override
    protected WindowManager.LayoutParams getSearchLayoutParams(LayoutParams layoutParams) {
        boolean opaque = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                (opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT));
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("SearchPanel");
        // TODO: Define custom animation for Search panel
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    @Override
    protected void updateSearchPanel() {
        super.updateSearchPanel();
        if (mNavigationBarView != null) {
            mNavigationBarView.setDelegateView(mSearchPanelView);
        }
    }

    @Override
    public void showSearchPanel() {
        super.showSearchPanel();
        mHandler.removeCallbacks(mShowSearchPanel);

        // we want to freeze the sysui state wherever it is
        mSearchPanelView.setSystemUiVisibility(mSystemUiVisibility);

        if (mNavigationBarView != null) {
            WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mWindowManager.updateViewLayout(mNavigationBarView, lp);
        }
    }

    @Override
    public void hideSearchPanel() {
        super.hideSearchPanel();
        if (mNavigationBarView != null) {
            WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mWindowManager.updateViewLayout(mNavigationBarView, lp);
        }
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

    private int mShowSearchHoldoff = 0;
    private Runnable mShowSearchPanel = new Runnable() {
        public void run() {
            showSearchPanel();
            awakenDreams();
        }
    };

    View.OnTouchListener mHomeActionListener = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!shouldDisableNavbarGestures()) {
                    mHandler.removeCallbacks(mShowSearchPanel);
                    mHandler.postDelayed(mShowSearchPanel, mShowSearchHoldoff);
                }
            break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mShowSearchPanel);
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

        mNavigationBarView.getRecentsButton().setOnClickListener(mRecentsClickListener);
        mNavigationBarView.getRecentsButton().setOnTouchListener(mRecentsPreloadOnTouchListener);
        mNavigationBarView.getHomeButton().setOnTouchListener(mHomeActionListener);
        updateSearchPanel();
    }

    // For small-screen devices (read: phones) that lack hardware navigation buttons
    private void addNavigationBar() {
        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + mNavigationBarView);
        if (mNavigationBarView == null) return;

        prepareNavigationBarView();

        mWindowManager.addView(mNavigationBarView, getNavigationBarLayoutParams());
    }

    private void repositionNavigationBar() {
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

    private void addHeadsUpView() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL, // above the status bar!
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        lp.gravity = Gravity.TOP;
        lp.setTitle("Heads Up");
        lp.packageName = mContext.getPackageName();
        lp.windowAnimations = R.style.Animation_StatusBar_HeadsUp;

        mWindowManager.addView(mHeadsUpNotificationView, lp);
    }

    private void removeHeadsUpView() {
        mWindowManager.removeView(mHeadsUpNotificationView);
    }

    public void refreshAllStatusBarIcons() {
        refreshAllIconsForLayout(mStatusIcons);
        refreshAllIconsForLayout(mNotificationIcons);
    }

    private void refreshAllIconsForLayout(LinearLayout ll) {
        final int count = ll.getChildCount();
        for (int n = 0; n < count; n++) {
            View child = ll.getChildAt(n);
            if (child instanceof StatusBarIconView) {
                ((StatusBarIconView) child).updateDrawable();
            }
        }
    }

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        if (SPEW) Log.d(TAG, "addIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " icon=" + icon);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(mIconSize, mIconSize));
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        if (SPEW) Log.d(TAG, "updateIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " old=" + old + " icon=" + icon);
        StatusBarIconView view = (StatusBarIconView)mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        if (SPEW) Log.d(TAG, "removeIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex);
        mStatusIcons.removeViewAt(viewIndex);
    }

    public UserHandle getCurrentUserHandle() {
        return new UserHandle(mCurrentUserId);
    }

    @Override
    public void addNotificationInternal(StatusBarNotification notification, Ranking ranking) {
        if (DEBUG) Log.d(TAG, "addNotification score=" + notification.getScore());
        Entry shadeEntry = createNotificationViews(notification);
        if (shadeEntry == null) {
            return;
        }
        if (mZenMode != Global.ZEN_MODE_OFF && mIntercepted.tryIntercept(notification, ranking)) {
            // Forward the ranking so we can sort the new notification.
            mNotificationData.updateRanking(ranking);
            return;
        }
        if (mUseHeadsUp && shouldInterrupt(notification)) {
            if (DEBUG) Log.d(TAG, "launching notification in heads up mode");
            Entry interruptionCandidate = new Entry(notification, null);
            ViewGroup holder = mHeadsUpNotificationView.getHolder();
            if (inflateViewsForHeadsUp(interruptionCandidate, holder)) {
                mInterruptingNotificationTime = System.currentTimeMillis();
                mInterruptingNotificationEntry = interruptionCandidate;
                shadeEntry.setInterruption();

                // 1. Populate mHeadsUpNotificationView
                mHeadsUpNotificationView.setNotification(mInterruptingNotificationEntry);

                // 2. Animate mHeadsUpNotificationView in
                mHandler.sendEmptyMessage(MSG_SHOW_HEADS_UP);

                // 3. Set alarm to age the notification off
                resetHeadsUpDecayTimer();
            }
        } else if (notification.getNotification().fullScreenIntent != null) {
            // Stop screensaver if the notification has a full-screen intent.
            // (like an incoming phone call)
            awakenDreams();

            // not immersive & a full-screen alert should be shown
            if (DEBUG) Log.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
            try {
                notification.getNotification().fullScreenIntent.send();
            } catch (PendingIntent.CanceledException e) {
            }
        } else {
            // usual case: status bar visible & not immersive

            // show the ticker if there isn't already a heads up
            if (mInterruptingNotificationEntry == null) {
                tick(notification, true);
            }
        }
        addNotificationViews(shadeEntry, ranking);
        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    @Override
    public void resetHeadsUpDecayTimer() {
        mHandler.removeMessages(MSG_HIDE_HEADS_UP);
        if (mUseHeadsUp && mHeadsUpNotificationDecay > 0
                && mHeadsUpNotificationView.isClearable()) {
            mHandler.sendEmptyMessageDelayed(MSG_HIDE_HEADS_UP, mHeadsUpNotificationDecay);
        }
    }

    @Override
    public void updateNotificationInternal(StatusBarNotification notification, Ranking ranking) {
        super.updateNotificationInternal(notification, ranking);
        mIntercepted.update(notification);
    }

    @Override
    protected void updateRankingInternal(Ranking ranking) {
        mNotificationData.updateRanking(ranking);
        mIntercepted.retryIntercepts(ranking);
        updateNotifications();
    }

    @Override
    public void removeNotificationInternal(String key, Ranking ranking) {
        StatusBarNotification old = removeNotificationViews(key, ranking);
        if (SPEW) Log.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            // Cancel the ticker if it's still running
            mTicker.removeEntry(old);

            // Recalculate the position of the sliding windows and the titles.
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

            if (ENABLE_HEADS_UP && mInterruptingNotificationEntry != null
                    && old == mInterruptingNotificationEntry.notification) {
                mHandler.sendEmptyMessage(MSG_HIDE_HEADS_UP);
            }

            if (CLOSE_PANEL_WHEN_EMPTIED && mNotificationData.size() == 0
                    && !mNotificationPanel.isTracking() && mState != StatusBarState.KEYGUARD) {
                animateCollapsePanels();
            }
        }
        mIntercepted.remove(key);
        setAreThereNotifications();
    }

    @Override
    protected void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }
        refreshAllStatusBarIcons();
    }

    private void updateShowSearchHoldoff() {
        mShowSearchHoldoff = mContext.getResources().getInteger(
            R.integer.config_show_search_delay);
    }

    private void updateNotificationShade() {
        if (mStackScroller == null) return;

        int N = mNotificationData.size();

        ArrayList<View> toShow = new ArrayList<View>();

        final boolean provisioned = isDeviceProvisioned();
        // If the device hasn't been through Setup, we only show system notifications
        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(i);
            if (!(provisioned || showNotificationEvenIfUnprovisioned(ent.notification))) continue;

            // TODO How do we want to badge notifcations from profiles.
            if (!notificationIsForCurrentProfiles(ent.notification)) continue;

            final int vis = ent.notification.getNotification().visibility;
            if (vis != Notification.VISIBILITY_SECRET) {
                // when isLockscreenPublicMode() we show the public form of VISIBILITY_PRIVATE notifications
                ent.row.setShowingPublic(isLockscreenPublicMode()
                        && vis == Notification.VISIBILITY_PRIVATE
                        && !userAllowsPrivateNotificationsInPublic(ent.notification.getUserId()));
                toShow.add(ent.row);
            }
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i< mStackScroller.getChildCount(); i++) {
            View child = mStackScroller.getChildAt(i);
            if (!toShow.contains(child) && child instanceof ExpandableNotificationRow) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mStackScroller.removeView(remove);
        }

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mStackScroller.addView(v);
            }
        }

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

            if (child == toShow.get(j)) {
                // Everything is well, advance both lists.
                j++;
                continue;
            }

            // Oops, wrong notification at this position. Put the right one
            // here and advance both lists.
            mStackScroller.changeViewPosition(toShow.get(j), i);
            j++;
        }
        updateRowStates();
        updateSpeedbump();
        mNotificationPanel.setQsExpansionEnabled(provisioned && mUserSetup);
    }

    private void updateSpeedbump() {
        int speedbumpIndex = -1;
        int currentIndex = 0;
        for (int i = 0; i < mNotificationData.size(); i++) {
            Entry entry = mNotificationData.get(i);
            if (entry.row.getParent() == null) {
                // This view isn't even added, so the stack scroller doesn't
                // know about it. Ignore completely.
                continue;
            }
            if (entry.row.getVisibility() != View.GONE &&
                    mNotificationData.isAmbient(entry.key)) {
                speedbumpIndex = currentIndex;
                break;
            }
            currentIndex++;
        }
        mStackScroller.updateSpeedBumpIndex(speedbumpIndex);
    }

    @Override
    protected void updateNotifications() {
        // TODO: Move this into updateNotificationIcons()?
        if (mNotificationIcons == null) return;

        updateNotificationShade();
        updateNotificationIcons();
    }

    private void updateNotificationIcons() {
        final LinearLayout.LayoutParams params
            = new LinearLayout.LayoutParams(mIconSize + 2*mIconHPadding, mNaturalBarHeight);

        int N = mNotificationData.size();

        if (DEBUG) {
            Log.d(TAG, "refreshing icons: " + N + " notifications, mNotificationIcons=" +
                    mNotificationIcons);
        }

        ArrayList<View> toShow = new ArrayList<View>();

        final boolean provisioned = isDeviceProvisioned();
        // If the device hasn't been through Setup, we only show system notifications
        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(i);
            if (!((provisioned && ent.notification.getScore() >= HIDE_ICONS_BELOW_SCORE)
                    || showNotificationEvenIfUnprovisioned(ent.notification))) continue;
            if (!notificationIsForCurrentProfiles(ent.notification)) continue;
            if (isLockscreenPublicMode()
                    && ent.notification.getNotification().visibility
                            == Notification.VISIBILITY_SECRET
                    && !userAllowsPrivateNotificationsInPublic(ent.notification.getUserId())) {
                // in "public" mode (atop a secure keyguard), secret notifs are totally hidden
                continue;
            }
            if (mIntercepted.isSyntheticEntry(ent)) {
                continue;
            }
            toShow.add(ent.icon);
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i<mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mNotificationIcons.removeView(remove);
        }

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mNotificationIcons.addView(v, i, params);
            }
        }
    }

    protected void updateCarrierLabelVisibility(boolean force) {
        // TODO: Handle this for the notification stack scroller as well
        if (!mShowCarrierInPanel) return;
        // The idea here is to only show the carrier label when there is enough room to see it,
        // i.e. when there aren't enough notifications to fill the panel.
        if (SPEW) {
            Log.d(TAG, String.format("stackScrollerh=%d scrollh=%d carrierh=%d",
                    mStackScroller.getHeight(), mStackScroller.getHeight(),
                    mCarrierLabelHeight));
        }

        final boolean emergencyCallsShownElsewhere = mEmergencyCallLabel != null;
        final boolean makeVisible =
            !(emergencyCallsShownElsewhere && mNetworkController.isEmergencyOnly())
            && mStackScroller.getHeight() < (mNotificationPanel.getHeight()
                    - mCarrierLabelHeight - mStatusBarHeaderHeight)
            && mStackScroller.getVisibility() == View.VISIBLE
            && mState != StatusBarState.KEYGUARD;

        if (force || mCarrierLabelVisible != makeVisible) {
            mCarrierLabelVisible = makeVisible;
            if (DEBUG) {
                Log.d(TAG, "making carrier label " + (makeVisible?"visible":"invisible"));
            }
            mCarrierLabel.animate().cancel();
            if (makeVisible) {
                mCarrierLabel.setVisibility(View.VISIBLE);
            }
            mCarrierLabel.animate()
                .alpha(makeVisible ? 1f : 0f)
                //.setStartDelay(makeVisible ? 500 : 0)
                //.setDuration(makeVisible ? 750 : 100)
                .setDuration(150)
                .setListener(makeVisible ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!mCarrierLabelVisible) { // race
                            mCarrierLabel.setVisibility(View.INVISIBLE);
                            mCarrierLabel.setAlpha(0f);
                        }
                    }
                })
                .start();
        }
    }

    @Override
    protected void setAreThereNotifications() {
        final boolean any = mNotificationData.size() > 0;

        final boolean clearable = any && mNotificationData.hasClearableItems();

        if (SPEW) {
            Log.d(TAG, "setAreThereNotifications: N=" + mNotificationData.size()
                    + " any=" + any + " clearable=" + clearable);
        }

        final View nlo = mStatusBarView.findViewById(R.id.notification_lights_out);
        final boolean showDot = (any&&!areLightsOn());
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

        updateCarrierLabelVisibility(false);
    }

    public void showClock(boolean show) {
        if (mStatusBarView == null) return;
        View clock = mStatusBarView.findViewById(R.id.clock);
        if (clock != null) {
            clock.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    public void disable(int state) {
        final int old = mDisabled;
        final int diff = state ^ old;
        mDisabled = state;

        if (DEBUG) {
            Log.d(TAG, String.format("disable: 0x%08x -> 0x%08x (diff: 0x%08x)",
                old, state, diff));
        }

        StringBuilder flagdbg = new StringBuilder();
        flagdbg.append("disable: < ");
        flagdbg.append(((state & StatusBarManager.DISABLE_EXPAND) != 0) ? "EXPAND" : "expand");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_EXPAND) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "ICONS" : "icons");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "ALERTS" : "alerts");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "SYSTEM_INFO" : "system_info");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_BACK) != 0) ? "BACK" : "back");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_BACK) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_HOME) != 0) ? "HOME" : "home");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_HOME) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_RECENT) != 0) ? "RECENT" : "recent");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_RECENT) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_CLOCK) != 0) ? "CLOCK" : "clock");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_CLOCK) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_SEARCH) != 0) ? "SEARCH" : "search");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_SEARCH) != 0) ? "* " : " ");
        flagdbg.append(">");
        Log.d(TAG, flagdbg.toString());

        if ((diff & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
            mSystemIconArea.animate().cancel();
            if ((state & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
                mSystemIconArea.animate()
                    .alpha(0f)
                    .translationY(mNaturalBarHeight*0.5f)
                    .setDuration(175)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(mMakeIconsInvisible)
                    .start();
            } else {
                mSystemIconArea.setVisibility(View.VISIBLE);
                mSystemIconArea.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(0)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(175)
                    .start();
            }
        }

        if ((diff & StatusBarManager.DISABLE_CLOCK) != 0) {
            boolean show = (state & StatusBarManager.DISABLE_CLOCK) == 0;
            showClock(show);
        }
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state & StatusBarManager.DISABLE_EXPAND) != 0) {
                animateCollapsePanels();
            }
        }

        if ((diff & (StatusBarManager.DISABLE_HOME
                        | StatusBarManager.DISABLE_RECENT
                        | StatusBarManager.DISABLE_BACK
                        | StatusBarManager.DISABLE_SEARCH)) != 0) {
            // the nav bar will take care of these
            if (mNavigationBarView != null) mNavigationBarView.setDisabledFlags(state);

            if ((state & StatusBarManager.DISABLE_RECENT) != 0) {
                // close recents if it's visible
                mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
                mHandler.sendEmptyMessage(MSG_HIDE_RECENT_APPS);
            }
        }

        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                if (mTicking) {
                    haltTicker();
                }

                mNotificationIcons.animate()
                    .alpha(0f)
                    .translationY(mNaturalBarHeight*0.5f)
                    .setDuration(175)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(mMakeIconsInvisible)
                    .start();
            } else {
                mNotificationIcons.setVisibility(View.VISIBLE);
                mNotificationIcons.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(0)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(175)
                    .start();
            }
        }
    }

    @Override
    protected BaseStatusBar.H createHandler() {
        return new PhoneStatusBar.H();
    }

    @Override
    public void startActivity(Intent intent) {
        startActivityDismissingKeyguard(intent, false);
    }

    public ScrimController getScrimController() {
        return mScrimController;
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
                    animateExpandSettingsPanel();
                    break;
                case MSG_CLOSE_PANELS:
                    animateCollapsePanels();
                    break;
                case MSG_SHOW_HEADS_UP:
                    setHeadsUpVisibility(true);
                    break;
                case MSG_HIDE_HEADS_UP:
                    setHeadsUpVisibility(false);
                    break;
                case MSG_ESCALATE_HEADS_UP:
                    escalateHeadsUp();
                    setHeadsUpVisibility(false);
                    break;
            }
        }
    }

    /**  if the interrupting notification had a fullscreen intent, fire it now.  */
    private void escalateHeadsUp() {
        if (mInterruptingNotificationEntry != null) {
            final StatusBarNotification sbn = mInterruptingNotificationEntry.notification;
            final Notification notification = sbn.getNotification();
            if (notification.fullScreenIntent != null) {
                if (DEBUG)
                    Log.d(TAG, "converting a heads up to fullScreen");
                try {
                    notification.fullScreenIntent.send();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        }
    }

    private Handler getHandler() {
        return mHandler;
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };

    boolean panelsEnabled() {
        return (mDisabled & StatusBarManager.DISABLE_EXPAND) == 0;
    }

    void makeExpandedVisible(boolean force) {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (!force && (mExpandedVisible || !panelsEnabled())) {
            return;
        }

        mExpandedVisible = true;
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(true);

        updateCarrierLabelVisibility(true);

        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        mStatusBarWindowManager.setStatusBarExpanded(true);

        visibilityChanged(true);

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

    public void animateCollapsePanels(int flags) {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            return;
        }
        if (SPEW) {
            Log.d(TAG, "animateCollapse():"
                    + " mExpandedVisible=" + mExpandedVisible
                    + " flags=" + flags);
        }

        if ((flags & CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL) == 0) {
            mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
            mHandler.sendEmptyMessage(MSG_HIDE_RECENT_APPS);
        }

        if ((flags & CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL) == 0) {
            mHandler.removeMessages(MSG_CLOSE_SEARCH_PANEL);
            mHandler.sendEmptyMessage(MSG_CLOSE_SEARCH_PANEL);
        }

        if (mStatusBarWindow != null) {

            // release focus immediately to kick off focus change transition
            mStatusBarWindowManager.setStatusBarFocusable(false);

            mStatusBarWindow.cancelExpandHelper();
            mStatusBarView.collapseAllPanels(true);
            if (isFlippedToSettings()) {
                flipToNotifications(true /*animate*/);
            }
        }
    }

    public ViewPropertyAnimator setVisibilityWhenDone(
            final ViewPropertyAnimator a, final View v, final int vis) {
        a.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
                a.setListener(null); // oneshot
            }
        });
        return a;
    }

    public Animator setVisibilityWhenDone(
            final Animator a, final View v, final int vis) {
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
            }
        });
        return a;
    }

    public Animator interpolator(TimeInterpolator ti, Animator a) {
        a.setInterpolator(ti);
        return a;
    }

    public Animator startDelay(int d, Animator a) {
        a.setStartDelay(d);
        return a;
    }

    public Animator start(Animator a) {
        a.start();
        return a;
    }

    final TimeInterpolator mAccelerateInterpolator = new AccelerateInterpolator();
    final TimeInterpolator mDecelerateInterpolator = new DecelerateInterpolator();
    final int FLIP_DURATION_OUT = 125;
    final int FLIP_DURATION_IN = 225;
    final int FLIP_DURATION = (FLIP_DURATION_IN + FLIP_DURATION_OUT);

    Animator mScrollViewAnim, mClearButtonAnim;

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
            return ;
        }

        mNotificationPanel.expand();
        if (mStackScroller.getVisibility() != View.VISIBLE) {
            flipToNotifications(true /*animate*/);
        }

        if (false) postStartTracing();
    }

    private static void cancelAnim(Animator anim) {
        if (anim != null) {
            anim.cancel();
        }
    }

    public void flipToNotifications(boolean animate) {
        // TODO: Animation
        mNotificationPanel.closeQs();
    }

    @Override
    public void animateExpandSettingsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
            return;
        }

        // Settings are not available in setup
        if (!mUserSetup) return;

        mNotificationPanel.expand();
        mNotificationPanel.openQs();

        if (false) postStartTracing();
    }

    public boolean isFlippedToSettings() {
        if (mNotificationPanel != null) {
            return mNotificationPanel.isQsExpanded();
        }
        return false;
    }

    public void animateCollapseQuickSettings() {
        mStatusBarView.collapseAllPanels(true);
    }

    void makeExpandedInvisibleSoon() {
        mHandler.postDelayed(new Runnable() { public void run() { makeExpandedInvisible(); }}, 50);
    }

    void makeExpandedInvisible() {
        if (SPEW) Log.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible || mStatusBarWindow == null) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mStatusBarView.collapseAllPanels(/*animate=*/ false);

        // reset things to their proper state
        if (mScrollViewAnim != null) mScrollViewAnim.cancel();
        if (mClearButtonAnim != null) mClearButtonAnim.cancel();

        mStackScroller.setVisibility(View.VISIBLE);
        mNotificationPanel.setVisibility(View.GONE);

        setAreThereNotifications(); // show the clear button

        mNotificationPanel.closeQs();

        mExpandedVisible = false;
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(false);
        visibilityChanged(false);

        // Shrink the window to the size of the status bar only
        mStatusBarWindowManager.setStatusBarExpanded(false);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }

        // Close any "App info" popups that might have snuck on-screen
        dismissPopups();

        if (mPostCollapseCleanup != null) {
            mPostCollapseCleanup.run();
            mPostCollapseCleanup = null;
        }

        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);

        showBouncer();
    }

    public boolean interceptTouchEvent(MotionEvent event) {
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_STATUSBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(), mDisabled);
            }

        }

        if (SPEW) {
            Log.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled + " mTracking=" + mTracking);
        } else if (CHATTY) {
            if (event.getAction() != MotionEvent.ACTION_MOVE) {
                Log.d(TAG, String.format(
                            "panel: %s at (%f, %f) mDisabled=0x%08x",
                            MotionEvent.actionToString(event.getAction()),
                            event.getRawX(), event.getRawY(), mDisabled));
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
            if (!showing) {
                mStatusBarView.collapseAllPanels(false);
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
    public void setSystemUiVisibility(int vis, int mask) {
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal&~mask) | (vis&mask);
        final int diff = newVal ^ oldVal;
        if (DEBUG) Log.d(TAG, String.format(
                "setSystemUiVisibility vis=%s mask=%s oldVal=%s newVal=%s diff=%s",
                Integer.toHexString(vis), Integer.toHexString(mask),
                Integer.toHexString(oldVal), Integer.toHexString(newVal),
                Integer.toHexString(diff)));
        if (diff != 0) {
            mSystemUiVisibility = newVal;

            // update low profile
            if ((diff & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
                final boolean lightsOut = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0;
                if (lightsOut) {
                    animateCollapsePanels();
                    if (mTicking) {
                        haltTicker();
                    }
                }

                setAreThereNotifications();
            }

            // update status bar mode
            final int sbMode = computeBarMode(oldVal, newVal, mStatusBarView.getBarTransitions(),
                    View.STATUS_BAR_TRANSIENT, View.STATUS_BAR_TRANSLUCENT);

            // update navigation bar mode
            final int nbMode = mNavigationBarView == null ? -1 : computeBarMode(
                    oldVal, newVal, mNavigationBarView.getBarTransitions(),
                    View.NAVIGATION_BAR_TRANSIENT, View.NAVIGATION_BAR_TRANSLUCENT);
            final boolean sbModeChanged = sbMode != -1;
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

            // ready to unhide
            if ((vis & View.STATUS_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.STATUS_BAR_UNHIDE;
            }
            if ((vis & View.NAVIGATION_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.NAVIGATION_BAR_UNHIDE;
            }

            // send updated sysui visibility to window manager
            notifyUiVisibilityChanged(mSystemUiVisibility);
        }
    }

    private int computeBarMode(int oldVis, int newVis, BarTransitions transitions,
            int transientFlag, int translucentFlag) {
        final int oldMode = barMode(oldVis, transientFlag, translucentFlag);
        final int newMode = barMode(newVis, transientFlag, translucentFlag);
        if (oldMode == newMode) {
            return -1; // no mode change
        }
        return newMode;
    }

    private int barMode(int vis, int transientFlag, int translucentFlag) {
        return (vis & transientFlag) != 0 ? MODE_SEMI_TRANSPARENT
                : (vis & translucentFlag) != 0 ? MODE_TRANSLUCENT
                : (vis & View.SYSTEM_UI_TRANSPARENT) != 0 ? MODE_TRANSPARENT
                : (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0 ? MODE_LIGHTS_OUT
                : MODE_OPAQUE;
    }

    private void checkBarModes() {
        if (mDemoMode) return;
        checkBarMode(mStatusBarMode, mStatusBarWindowState, mStatusBarView.getBarTransitions());
        if (mNavigationBarView != null) {
            checkBarMode(mNavigationBarMode,
                    mNavigationBarWindowState, mNavigationBarView.getBarTransitions());
        }
    }

    private void checkBarMode(int mode, int windowState, BarTransitions transitions) {
        final boolean anim = (mScreenOn == null || mScreenOn) && windowState != WINDOW_STATE_HIDDEN;
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
        }};

    @Override
    public void setInteracting(int barWindow, boolean interacting) {
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            suspendAutohide();
        } else {
            resumeSuspendedAutohide();
        }
        checkBarModes();
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
                ) {
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
            setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        } else {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    private void notifyUiVisibilityChanged(int vis) {
        try {
            mWindowManagerService.statusBarVisibilityChanged(vis);
        } catch (RemoteException ex) {
        }
    }

    public void topAppWindowChanged(boolean showMenu) {
        if (DEBUG) {
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

    @Override
    public void setHardKeyboardStatus(boolean available, boolean enabled) {}

    @Override
    protected void tick(StatusBarNotification n, boolean firstTime) {
        // no ticking in lights-out mode
        if (!areLightsOn()) return;

        // no ticking in Setup
        if (!isDeviceProvisioned()) return;

        // not for you
        if (!notificationIsForCurrentProfiles(n)) return;

        // Show the ticker if one is requested. Also don't do this
        // until status bar window is attached to the window manager,
        // because...  well, what's the point otherwise?  And trying to
        // run a ticker without being attached will crash!
        if (n.getNotification().tickerText != null && mStatusBarWindow != null
                && mStatusBarWindow.getWindowToken() != null) {
            if (0 == (mDisabled & (StatusBarManager.DISABLE_NOTIFICATION_ICONS
                            | StatusBarManager.DISABLE_NOTIFICATION_TICKER))) {
                mTicker.addEntry(n);
            }
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, View sb) {
            super(context, sb);
        }

        @Override
        public void tickerStarting() {
            mTicking = true;
            mStatusBarContents.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
        }

        @Override
        public void tickerDone() {
            mStatusBarContents.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
        }

        public void tickerHalting() {
            if (mStatusBarContents.getVisibility() != View.VISIBLE) {
                mStatusBarContents.setVisibility(View.VISIBLE);
                mStatusBarContents
                        .startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            }
            mTickerView.setVisibility(View.GONE);
            // we do not animate the ticker away at this point, just get rid of it (b/6992707)
        }
    }

    Animation.AnimationListener mTickingDoneListener = new Animation.AnimationListener() {;
        public void onAnimationEnd(Animation animation) {
            mTicking = false;
        }
        public void onAnimationRepeat(Animation animation) {
        }
        public void onAnimationStart(Animation animation) {
        }
    };

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(mContext, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
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
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }

        pw.print("  mInteractingWindows="); pw.println(mInteractingWindows);
        pw.print("  mStatusBarWindowState=");
        pw.println(windowStateToString(mStatusBarWindowState));
        pw.print("  mStatusBarMode=");
        pw.println(BarTransitions.modeToString(mStatusBarMode));
        pw.print("  mZenMode=");
        pw.println(Settings.Global.zenModeToString(mZenMode));
        pw.print("  mUseHeadsUp=");
        pw.println(mUseHeadsUp);
        pw.print("  interrupting package: ");
        pw.println(hunStateToString(mInterruptingNotificationEntry));
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

        pw.println("  Panels: ");
        if (mNotificationPanel != null) {
            pw.println("    mNotificationPanel=" +
                mNotificationPanel + " params=" + mNotificationPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanel.dump(fd, pw, args);
        }

        if (DUMPTRUCK) {
            synchronized (mNotificationData) {
                int N = mNotificationData.size();
                pw.println("  notification icons: " + N);
                for (int i=0; i<N; i++) {
                    NotificationData.Entry e = mNotificationData.get(i);
                    pw.println("    [" + i + "] key=" + e.key + " icon=" + e.icon);
                    StatusBarNotification n = e.notification;
                    pw.println("         pkg=" + n.getPackageName() + " id=" + n.getId() + " score=" + n.getScore());
                    pw.println("         notification=" + n.getNotification());
                    pw.println("         tickerText=\"" + n.getNotification().tickerText + "\"");
                }
            }

            int N = mStatusIcons.getChildCount();
            pw.println("  system icons: " + N);
            for (int i=0; i<N; i++) {
                StatusBarIconView ic = (StatusBarIconView) mStatusIcons.getChildAt(i);
                pw.println("    [" + i + "] icon=" + ic);
            }

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

        mNetworkController.dump(fd, pw, args);
    }

    private String hunStateToString(Entry entry) {
        if (entry == null) return "null";
        if (entry.notification == null) return "corrupt";
        return entry.notification.getPackageName();
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
        mStatusBarWindowManager.add(mStatusBarWindow, getStatusBarHeight());
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    static final float saturate(float a) {
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    @Override
    public void updateExpandedViewPos(int thingy) {
        if (SPEW) Log.v(TAG, "updateExpandedViewPos");

        // on larger devices, the notification panel is propped open a bit
        mNotificationPanel.setMinimumHeight(
                (int)(mNotificationPanelMinHeightFrac * mCurrentDisplaySize.y));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mNotificationPanel.getLayoutParams();
        lp.gravity = mNotificationPanelGravity;
        mNotificationPanel.setLayoutParams(lp);

        updateCarrierLabelVisibility(false);
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

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            synchronized (mNotificationData) {
                mPostCollapseCleanup = new Runnable() {
                    @Override
                    public void run() {
                        if (DEBUG) {
                            Log.v(TAG, "running post-collapse cleanup");
                        }
                        try {
                            mBarService.onClearAllNotifications(mCurrentUserId);
                        } catch (Exception ex) { }
                    }
                };

                animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                return;
                // TODO: Handle this better with notification stack scroller
            }
        }
    };

    public void startActivityDismissingKeyguard(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        animateCollapsePanels();
    }

    private View.OnClickListener mClockClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            startActivityDismissingKeyguard(
                    new Intent(Intent.ACTION_QUICK_CLOCK), true); // have fun, everyone
        }
    };

    private View.OnClickListener mNotificationButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            animateExpandNotificationsPanel();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                }
                animateCollapsePanels(flags);
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
                notifyNavigationBarScreenOn(false);
                notifyHeadsUpScreenOn(false);
                finishBarAnimations();
                stopNotificationLogging();
            }
            else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOn = true;
                // work around problem where mDisplay.getRotation() is not stable while screen is off (bug 7086018)
                repositionNavigationBar();
                notifyNavigationBarScreenOn(true);
                startNotificationLoggingIfScreenOnAndVisible();
            }
            else if (ACTION_DEMO.equals(action)) {
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
            }
        }
    };

    @Override
    protected void startNotificationActivity(OnDismissAction action) {
        if (mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBarKeyguardViewManager.dismissWithAction(action);
        } else {
            action.onDismiss();
        }
    }

    // SystemUIService notifies SystemBars of configuration changes, which then calls down here
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig); // calls refreshLayout

        if (DEBUG) {
            Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
        }
        updateDisplaySize(); // populates mDisplayMetrics

        updateResources();
        repositionNavigationBar();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        updateShowSearchHoldoff();
    }

    @Override
    public void userSwitched(int newUserId) {
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        animateCollapsePanels();
        updateNotifications();
        resetUserSetupObserver();
    }

    private void resetUserSetupObserver() {
        mContext.getContentResolver().unregisterContentObserver(mUserSetupObserver);
        mUserSetupObserver.onChange(false);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), true,
                mUserSetupObserver,
                mCurrentUserId);
    }

    private void setHeadsUpVisibility(boolean vis) {
        if (!ENABLE_HEADS_UP) return;
        if (DEBUG) Log.v(TAG, (vis ? "showing" : "hiding") + " heads up window");
        mHeadsUpNotificationView.setVisibility(vis ? View.VISIBLE : View.GONE);
        if (!vis) {
            if (DEBUG) Log.d(TAG, "setting heads up entry to null");
            mInterruptingNotificationEntry = null;
        }
    }

    public void onHeadsUpDismissed() {
        if (mInterruptingNotificationEntry == null) return;
        mHandler.sendEmptyMessage(MSG_HIDE_HEADS_UP);
        if (mHeadsUpNotificationView.isClearable()) {
            try {
                mBarService.onNotificationClear(
                        mInterruptingNotificationEntry.notification.getPackageName(),
                        mInterruptingNotificationEntry.notification.getTag(),
                        mInterruptingNotificationEntry.notification.getId(),
                        mInterruptingNotificationEntry.notification.getUserId());
            } catch (android.os.RemoteException ex) {
                // oh well
            }
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
        if (mQSPanel != null) mQSPanel.updateResources();

        loadDimens();
    }

    protected void loadDimens() {
        final Resources res = mContext.getResources();

        mNaturalBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        int newIconSize = res.getDimensionPixelSize(
            com.android.internal.R.dimen.status_bar_icon_size);
        int newIconHPadding = res.getDimensionPixelSize(
            R.dimen.status_bar_icon_padding);

        if (newIconHPadding != mIconHPadding || newIconSize != mIconSize) {
//            Log.d(TAG, "size=" + newIconSize + " padding=" + newIconHPadding);
            mIconHPadding = newIconHPadding;
            mIconSize = newIconSize;
            //reloadAllNotificationIcons(); // reload the tray
        }

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        mSelfExpandVelocityPx = res.getDimension(R.dimen.self_expand_velocity);
        mSelfCollapseVelocityPx = res.getDimension(R.dimen.self_collapse_velocity);
        mFlingExpandMinVelocityPx = res.getDimension(R.dimen.fling_expand_min_velocity);
        mFlingCollapseMinVelocityPx = res.getDimension(R.dimen.fling_collapse_min_velocity);

        mCollapseMinDisplayFraction = res.getFraction(R.dimen.collapse_min_display_fraction, 1, 1);
        mExpandMinDisplayFraction = res.getFraction(R.dimen.expand_min_display_fraction, 1, 1);

        mExpandAccelPx = res.getDimension(R.dimen.expand_accel);
        mCollapseAccelPx = res.getDimension(R.dimen.collapse_accel);

        mFlingGestureMaxXVelocityPx = res.getDimension(R.dimen.fling_gesture_max_x_velocity);

        mFlingGestureMaxOutputVelocityPx = res.getDimension(R.dimen.fling_gesture_max_output_velocity);

        mNotificationPanelGravity = res.getInteger(R.integer.notification_panel_layout_gravity);
        if (mNotificationPanelGravity <= 0) {
            mNotificationPanelGravity = Gravity.START | Gravity.TOP;
        }

        mCarrierLabelHeight = res.getDimensionPixelSize(R.dimen.carrier_label_height);
        mStatusBarHeaderHeight = res.getDimensionPixelSize(R.dimen.status_bar_header_height);

        mNotificationPanelMinHeightFrac = res.getFraction(R.dimen.notification_panel_min_height_frac, 1, 1);
        if (mNotificationPanelMinHeightFrac < 0f || mNotificationPanelMinHeightFrac > 1f) {
            mNotificationPanelMinHeightFrac = 0f;
        }

        mHeadsUpNotificationDecay = res.getInteger(R.integer.heads_up_notification_decay);
        mRowMinHeight =  res.getDimensionPixelSize(R.dimen.notification_min_height);
        mRowMaxHeight =  res.getDimensionPixelSize(R.dimen.notification_max_height);

        mKeyguardMaxNotificationCount = res.getInteger(R.integer.keyguard_max_notification_count);

        if (false) Log.v(TAG, "updateResources");
    }

    // Visibility reporting

    @Override
    protected void visibilityChanged(boolean visible) {
        mVisible = visible;
        if (visible) {
            startNotificationLoggingIfScreenOnAndVisible();
        } else {
            stopNotificationLogging();
        }
        super.visibilityChanged(visible);
    }

    private void stopNotificationLogging() {
        // Report all notifications as invisible and turn down the
        // reporter.
        if (!mCurrentlyVisibleNotifications.isEmpty()) {
            logNotificationVisibilityChanges(
                    Collections.<String>emptyList(), mCurrentlyVisibleNotifications);
            mCurrentlyVisibleNotifications.clear();
        }
        mHandler.removeCallbacks(mVisibilityReporter);
        mStackScroller.setChildLocationsChangedListener(null);
    }

    private void startNotificationLoggingIfScreenOnAndVisible() {
        if (mVisible && mScreenOn) {
            mStackScroller.setChildLocationsChangedListener(mNotificationLocationsChangedListener);
            // Some transitions like mScreenOn=false -> mScreenOn=true don't
            // cause the scroller to emit child location events. Hence generate
            // one ourselves to guarantee that we're reporting visible
            // notifications.
            // (Note that in cases where the scroller does emit events, this
            // additional event doesn't break anything.)
            mNotificationLocationsChangedListener.onChildLocationsChanged(mStackScroller);
        }
    }

    private void logNotificationVisibilityChanges(
            Collection<String> newlyVisible, Collection<String> noLongerVisible) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return;
        }

        String[] newlyVisibleAr = newlyVisible.toArray(new String[newlyVisible.size()]);
        String[] noLongerVisibleAr = noLongerVisible.toArray(new String[noLongerVisible.size()]);
        try {
            mBarService.onNotificationVisibilityChanged(newlyVisibleAr, noLongerVisibleAr);
        } catch (RemoteException e) {
            // Ignore.
        }
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
        vib.vibrate(250, AudioManager.STREAM_SYSTEM);
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
    protected void haltTicker() {
        mTicker.halt();
    }

    @Override
    protected boolean shouldDisableNavbarGestures() {
        return !isDeviceProvisioned()
                || mExpandedVisible
                || (mDisabled & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    public void postStartSettingsActivity(final Intent intent, int delay) {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                handleStartSettingsActivity(intent, true /*onlyProvisioned*/);
            }
        }, delay);
    }

    private void handleStartSettingsActivity(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        animateCollapsePanels();
    }

    public void startSettingsActivity(String action) {
        postStartSettingsActivity(new Intent(action), 0);
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
        public void setColorFilter(ColorFilter cf) {
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
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    private boolean mDemoModeAllowed;
    private boolean mDemoMode;
    private DemoStatusIcons mDemoStatusIcons;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoModeAllowed) {
            mDemoModeAllowed = Settings.Global.getInt(mContext.getContentResolver(),
                    "sysui_demo_allowed", 0) != 0;
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
        if (modeChange || command.equals(COMMAND_CLOCK)) {
            dispatchDemoCommandToView(command, args, R.id.clock);
        }
        if (modeChange || command.equals(COMMAND_BATTERY)) {
            dispatchDemoCommandToView(command, args, R.id.battery);
        }
        if (modeChange || command.equals(COMMAND_STATUS)) {
            if (mDemoStatusIcons == null) {
                mDemoStatusIcons = new DemoStatusIcons(mStatusIcons, mIconSize);
            }
            mDemoStatusIcons.dispatchDemoCommand(command, args);
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

    public void showKeyguard() {
        setBarState(StatusBarState.KEYGUARD);
        updateKeyguardState();
        instantExpandNotificationsPanel();
        mLeaveOpenOnKeyguardHide = false;
    }

    public void hideKeyguard() {
        setBarState(StatusBarState.SHADE);
        if (mLeaveOpenOnKeyguardHide) {
            mLeaveOpenOnKeyguardHide = false;
            mNotificationPanel.animateToFullShade();
        } else {
            instantCollapseNotificationPanel();
        }
        updateKeyguardState();
    }

    private void updatePublicMode() {
        setLockscreenPublicMode(mState == StatusBarState.KEYGUARD
                && mStatusBarKeyguardViewManager.isSecure());
    }

    private void updateKeyguardState() {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardStatusView.setVisibility(View.VISIBLE);
            mKeyguardIndicationTextView.setVisibility(View.VISIBLE);
            mKeyguardIndicationTextView.switchIndication(mKeyguardHotwordPhrase);
            mNotificationPanel.resetViews();
        } else {
            mKeyguardStatusView.setVisibility(View.GONE);
            mKeyguardIndicationTextView.setVisibility(View.GONE);
        }
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            mKeyguardBottomArea.setVisibility(View.VISIBLE);
            mHeader.setKeyguardShowing(true);
            mNotificationPanel.setKeyguardShowing(true);
            mScrimController.setKeyguardShowing(true);
        } else {
            mKeyguardBottomArea.setVisibility(View.GONE);
            mHeader.setKeyguardShowing(false);
            mNotificationPanel.setKeyguardShowing(false);
            mScrimController.setKeyguardShowing(false);
        }

        updateStackScrollerState();
        updatePublicMode();
        updateNotifications();
        checkBarModes();
        updateCarrierLabelVisibility(false);
    }

    public void updateStackScrollerState() {
        if (mStackScroller == null) return;
        mStackScroller.setDimmed(mState == StatusBarState.KEYGUARD, false /* animate */);
        mStackScroller.setVisibility(!mShowLockscreenNotifications && mState == StatusBarState.KEYGUARD
                ? View.INVISIBLE : View.VISIBLE);
    }

    public void userActivity() {
        mHandler.removeCallbacks(mUserActivity);
        mHandler.post(mUserActivity);
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mState == StatusBarState.KEYGUARD
                && mStatusBarKeyguardViewManager.interceptMediaKey(event);
    }

    public boolean onMenuPressed() {
        return mState == StatusBarState.KEYGUARD && mStatusBarKeyguardViewManager.onMenuPressed();
    }

    public boolean onBackPressed() {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            return mStatusBarKeyguardViewManager.onBackPressed();
        } else {
            animateCollapsePanels();
            return true;
        }
    }

    private void showBouncer() {
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            mStatusBarKeyguardViewManager.dismiss();
        }
    }

    private void instantExpandNotificationsPanel() {

        // Make our window larger and the panel visible.
        makeExpandedVisible(true);
        mNotificationPanel.setVisibility(View.VISIBLE);

        // Wait for window manager to pickup the change, so we know the maximum height of the panel
        // then.
        mNotificationPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (mStatusBarWindow.getHeight() != getStatusBarHeight()) {
                    mNotificationPanel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    mNotificationPanel.setExpandedFraction(1);
                }
            }
        });
    }

    private void instantCollapseNotificationPanel() {
        mNotificationPanel.setExpandedFraction(0);
    }

    @Override
    public void onActivated(View view) {
        userActivity();
        mKeyguardIndicationTextView.switchIndication(R.string.notification_tap_again);
        mStackScroller.setActivatedChild(view);
    }

    /**
     * @param state The {@link StatusBarState} to set.
     */
    public void setBarState(int state) {
        mState = state;
        mStatusBarWindowManager.setStatusBarState(state);
    }

    @Override
    public void onActivationReset(View view) {
        if (view == mStackScroller.getActivatedChild()) {
            mKeyguardIndicationTextView.switchIndication(mKeyguardHotwordPhrase);
            mStackScroller.setActivatedChild(null);
        }
    }

    public void onTrackingStarted() {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardIndicationTextView.switchIndication(R.string.keyguard_unlock);
        }
    }

    public void onTrackingStopped(boolean expand) {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardIndicationTextView.switchIndication(mKeyguardHotwordPhrase);
        }
        if (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED) {
            if (!expand && !mUnlockMethodCache.isMethodInsecure()) {
                showBouncer();
            }
        }
    }

    @Override
    protected int getMaxKeyguardNotifications() {
        return mKeyguardMaxNotificationCount;
    }

    public NavigationBarView getNavigationBarView() {
        return mNavigationBarView;
    }

    // ---------------------- DragDownHelper.OnDragDownListener ------------------------------------

    @Override
    public void onDraggedDown(View startingChild) {
        goToLockedShade(startingChild);
    }

    @Override
    public void onDragDownReset() {
        mStackScroller.setDimmed(true /* dimmed */, true /* animated */);
    }

    public void onThresholdReached() {
        mStackScroller.setDimmed(false /* dimmed */, true /* animate */);
    }

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * <p>If secure without redaction or no security: Go to {@link StatusBarState#SHADE_LOCKED}.</p>
     *
     * @param expandView The view to expand after going to the shade.
     */
    public void goToLockedShade(View expandView) {
        if (expandView instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) expandView;
            row.setUserExpanded(true);
        }
        if (isLockscreenPublicMode() && !userAllowsPrivateNotificationsInPublic(mCurrentUserId)) {
            mLeaveOpenOnKeyguardHide = true;
            showBouncer();
        } else {
            mNotificationPanel.animateToFullShade();
            setBarState(StatusBarState.SHADE_LOCKED);
            updateKeyguardState();
        }
    }

    /**
     * @return a ViewGroup that spans the entire panel which contains the quick settings
     */
    public ViewGroup getQuickSettingsOverlayParent() {
        return mNotificationPanel;
    }

    public LinearLayout getSystemIcons() {
        return mSystemIcons;
    }

    /**
     * Reattaches the system icons to its normal parent in collapsed status bar.
     */
    public void reattachSystemIcons() {
        mSystemIconArea.addView(mSystemIcons, 0);
    }

    public void onScreenTurnedOff() {
        mStackScroller.setAnimationsEnabled(false);
    }

    public void onScreenTurnedOn() {
        mStackScroller.setAnimationsEnabled(true);
    }

    private final Runnable mUserActivity = new Runnable() {
        @Override
        public void run() {
            if (mState == StatusBarState.KEYGUARD) {
                mKeyguardViewMediatorCallback.userActivity();
            }
        }
    };
}
