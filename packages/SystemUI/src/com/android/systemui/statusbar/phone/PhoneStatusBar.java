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
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarNotification;
import com.android.systemui.R;
import com.android.systemui.recent.RecentTasksLoader;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.RotationToggle;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.policy.AutoRotateController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.IntruderAlertView;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NotificationRowLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class PhoneStatusBar extends BaseStatusBar {
    static final String TAG = "PhoneStatusBar";
    public static final boolean DEBUG = false;
    public static final boolean SPEW = DEBUG;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info

    // additional instrumentation for testing purposes; intended to be left on during development
    public static final boolean CHATTY = DEBUG;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    private static final boolean DIM_BEHIND_EXPANDED_PANEL = false;

    private static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
    private static final int MSG_CLOSE_NOTIFICATION_PANEL = 1001;
    // 1020-1030 reserved for BaseStatusBar

    // will likely move to a resource or other tunable param at some point
    private static final int INTRUDER_ALERT_DECAY_MS = 0; // disabled, was 10000;

    private static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    private static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10; // see NotificationManagerService
    private static final int HIDE_ICONS_BELOW_SCORE = Notification.PRIORITY_LOW * NOTIFICATION_PRIORITY_MULTIPLIER;

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

    PhoneStatusBarPolicy mIconPolicy;

    // These are no longer handled by the policy, because we need custom strategies for them
    BatteryController mBatteryController;
    LocationController mLocationController;
    NetworkController mNetworkController;

    int mNaturalBarHeight = -1;
    int mIconSize = -1;
    int mIconHPadding = -1;
    Display mDisplay;

    IWindowManager mWindowManager;

    StatusBarWindowView mStatusBarWindow;
    PhoneStatusBarView mStatusBarView;

    int mPixelFormat;
    Object mQueueLock = new Object();

    // icons
    LinearLayout mIcons;
    IconMerger mNotificationIcons;
    View mMoreIcon;
    LinearLayout mStatusIcons;

    // expanded notifications
    View mNotificationPanel; // the sliding/resizing panel within the notification window
    ScrollView mScrollView;
    View mExpandedContents;
    int mNotificationPanelMarginBottomPx, mNotificationPanelMarginLeftPx;
    int mNotificationPanelGravity;

    // top bar
    View mClearButton;
    View mSettingsButton;
    RotationToggle mRotationButton;

    // drag bar
    CloseDragHandle mCloseView;
    private int mCloseViewHeight;

    // position
    int[] mPositionTmp = new int[2];
    boolean mExpanded;
    boolean mExpandedVisible;

    // the date view
    DateView mDateView;

    // for immersive activities
    private IntruderAlertView mIntruderAlertView;

    // on-screen navigation buttons
    private NavigationBarView mNavigationBarView = null;

    // the tracker view
    int mTrackingPosition; // the position of the top of the tracking view.
    private boolean mPanelSlightlyVisible;

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;

    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;

    Choreographer mChoreographer;
    boolean mAnimating;
    boolean mClosing; // only valid when mAnimating; indicates the initial acceleration
    float mAnimY;
    float mAnimVel;
    float mAnimAccel;
    long mAnimLastTimeNanos;
    boolean mAnimatingReveal = false;
    int mViewDelta;
    int[] mAbsPos = new int[2];
    Runnable mPostCollapseCleanup = null;

    private AnimatorSet mLightsOutAnimation;
    private AnimatorSet mLightsOnAnimation;

    // for disabling the status bar
    int mDisabled = 0;

    // tracking calls to View.setSystemUiVisibility()
    int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;

    DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    private int mNavigationIconHints = 0;

    private class ExpandedDialog extends Dialog {
        ExpandedDialog(Context context) {
            super(context, com.android.internal.R.style.Theme_Translucent_NoTitleBar);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    animateCollapse();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();

        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));

        super.start(); // calls createAndAddWindows()

        addNavigationBar();

        if (ENABLE_INTRUDERS) addIntruderView();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext);
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
        if (DEBUG) {
            mStatusBarWindow.setBackgroundColor(0x6000FF80);
        }
        mStatusBarWindow.mService = this;
        mStatusBarWindow.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mExpanded && !mAnimating) {
                        animateCollapse();
                    }
                }
                return true;
            }});

        mStatusBarView = (PhoneStatusBarView) mStatusBarWindow.findViewById(R.id.status_bar);
        mNotificationPanel = mStatusBarWindow.findViewById(R.id.notification_panel);
        // don't allow clicks on the panel to pass through to the background where they will cause the panel to close
        mNotificationPanel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });

        if (!ActivityManager.isHighEndGfx(mDisplay)) {
            mStatusBarWindow.setBackground(null);
            mNotificationPanel.setBackgroundColor(context.getResources().getColor(
                    R.color.notification_panel_solid_background));
        }

        if (ENABLE_INTRUDERS) {
            mIntruderAlertView = (IntruderAlertView) View.inflate(context, R.layout.intruder_alert, null);
            mIntruderAlertView.setVisibility(View.GONE);
            mIntruderAlertView.setBar(this);
        }

        mStatusBarView.mService = this;

        mChoreographer = Choreographer.getInstance();

        try {
            boolean showNav = mWindowManager.hasNavigationBar();
            if (DEBUG) Slog.v(TAG, "hasNavigationBar=" + showNav);
            if (showNav) {
                mNavigationBarView =
                    (NavigationBarView) View.inflate(context, R.layout.navigation_bar, null);

                mNavigationBarView.setDisabledFlags(mDisabled);
                mNavigationBarView.setBar(this);
            }
        } catch (RemoteException ex) {
            // no window manager? good luck with that
        }

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.OPAQUE;
        mStatusIcons = (LinearLayout)mStatusBarView.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)mStatusBarView.findViewById(R.id.notificationIcons);
        mMoreIcon = mStatusBarView.findViewById(R.id.moreIcon);
        mNotificationIcons.setOverflowIndicator(mMoreIcon);
        mIcons = (LinearLayout)mStatusBarView.findViewById(R.id.icons);
        mTickerView = mStatusBarView.findViewById(R.id.ticker);

        mPile = (NotificationRowLayout)mStatusBarWindow.findViewById(R.id.latestItems);
        mPile.setLongPressListener(getNotificationLongClicker());
        mExpandedContents = mPile; // was: expanded.findViewById(R.id.notificationLinearLayout);

        mClearButton = mStatusBarWindow.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mClearButton.setAlpha(0f);
        mClearButton.setEnabled(false);
        mDateView = (DateView)mStatusBarWindow.findViewById(R.id.date);
        mSettingsButton = mStatusBarWindow.findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(mSettingsButtonListener);
        mRotationButton = (RotationToggle) mStatusBarWindow.findViewById(R.id.rotation_lock_button);

        mScrollView = (ScrollView)mStatusBarWindow.findViewById(R.id.scroll);
        mScrollView.setVerticalScrollBarEnabled(false); // less drawing during pulldowns

        mTicker = new MyTicker(context, mStatusBarView);

        TickerView tickerView = (TickerView)mStatusBarView.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mCloseView = (CloseDragHandle)mStatusBarWindow.findViewById(R.id.close);
        mCloseView.mService = this;
        mCloseViewHeight = res.getDimensionPixelSize(R.dimen.close_handle_height);

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();

        // Other icons
        mLocationController = new LocationController(mContext); // will post a notification
        mBatteryController = new BatteryController(mContext);
        mBatteryController.addIconView((ImageView)mStatusBarView.findViewById(R.id.battery));
        mNetworkController = new NetworkController(mContext);
        final SignalClusterView signalCluster =
                (SignalClusterView)mStatusBarView.findViewById(R.id.signal_cluster);
        mNetworkController.addSignalCluster(signalCluster);
        signalCluster.setNetworkController(mNetworkController);
//        final ImageView wimaxRSSI =
//                (ImageView)sb.findViewById(R.id.wimax_signal);
//        if (wimaxRSSI != null) {
//            mNetworkController.addWimaxIconView(wimaxRSSI);
//        }
        // Recents Panel
        mRecentTasksLoader = new RecentTasksLoader(context);
        updateRecentsPanel();

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mBroadcastReceiver, filter);

        return mStatusBarView;
    }

    @Override
    protected WindowManager.LayoutParams getRecentsLayoutParams(LayoutParams layoutParams) {
        boolean opaque = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                layoutParams.width,
                layoutParams.height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                (opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT));
        if (ActivityManager.isHighEndGfx(mDisplay)) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0.7f;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.setTitle("RecentsPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
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
        if (ActivityManager.isHighEndGfx(mDisplay)) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0.7f;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.setTitle("SearchPanel");
        // TODO: Define custom animation for Search panel
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    protected void updateRecentsPanel() {
        super.updateRecentsPanel(R.layout.status_bar_recent_panel);
        // Make .03 alpha the minimum so you always see the item a bit-- slightly below
        // .03, the item disappears entirely (as if alpha = 0) and that discontinuity looks
        // a bit jarring
        mRecentsPanel.setMinSwipeAlpha(0.03f);
        if (mNavigationBarView != null) {
            mNavigationBarView.getRecentsButton().setOnTouchListener(mRecentsPanel);
        }
    }

    @Override
    protected void updateSearchPanel() {
        super.updateSearchPanel();
        mSearchPanelView.setStatusBarView(mNavigationBarView);
        mNavigationBarView.setDelegateView(mSearchPanelView);
    }

    @Override
    public void showSearchPanel() {
        super.showSearchPanel();
        WindowManager.LayoutParams lp =
            (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManagerImpl.getDefault().updateViewLayout(mNavigationBarView, lp);
    }

    @Override
    public void hideSearchPanel() {
        super.hideSearchPanel();
        WindowManager.LayoutParams lp =
            (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManagerImpl.getDefault().updateViewLayout(mNavigationBarView, lp);
    }

    protected int getStatusBarGravity() {
        return Gravity.TOP | Gravity.FILL_HORIZONTAL;
    }

    public int getStatusBarHeight() {
        if (mNaturalBarHeight < 0) {
            final Resources res = mContext.getResources();
            mNaturalBarHeight = 
                    res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        }
        return mNaturalBarHeight;
    }

    private int getCloseViewHeight() {
        return mCloseViewHeight;
    }

    private View.OnClickListener mRecentsClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            toggleRecentApps();
        }
    };

    View.OnTouchListener mHomeSearchActionListener = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            switch(event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Slog.d(TAG, "showing search panel");
                    showSearchPanel();
                break;

                case MotionEvent.ACTION_UP:
                    Slog.d(TAG, "hiding search panel");
                    hideSearchPanel();
                break;
            }
            return false;
        }
    };

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();

        mNavigationBarView.getRecentsButton().setOnClickListener(mRecentsClickListener);
        mNavigationBarView.getRecentsButton().setOnTouchListener(mRecentsPanel);
        updateSearchPanel();
//        mNavigationBarView.getHomeButton().setOnTouchListener(mHomeSearchActionListener);
    }

    // For small-screen devices (read: phones) that lack hardware navigation buttons
    private void addNavigationBar() {
        if (DEBUG) Slog.v(TAG, "addNavigationBar: about to add " + mNavigationBarView);
        if (mNavigationBarView == null) return;

        prepareNavigationBarView();

        WindowManagerImpl.getDefault().addView(
                mNavigationBarView, getNavigationBarLayoutParams());
    }

    private void repositionNavigationBar() {
        if (mNavigationBarView == null) return;

        prepareNavigationBarView();

        WindowManagerImpl.getDefault().updateViewLayout(
                mNavigationBarView, getNavigationBarLayoutParams());
    }

    private WindowManager.LayoutParams getNavigationBarLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    0
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE);
        // this will allow the navbar to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx(mDisplay)) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.setTitle("NavigationBar");
        lp.windowAnimations = 0;

        return lp;
    }

    private void addIntruderView() {
        final int height = getStatusBarHeight();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL, // above the status bar!
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        //lp.y += height * 1.5; // FIXME
        lp.setTitle("IntruderAlert");
        lp.packageName = mContext.getPackageName();
        lp.windowAnimations = R.style.Animation_StatusBar_IntruderAlert;

        WindowManagerImpl.getDefault().addView(mIntruderAlertView, lp);
    }

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        if (SPEW) Slog.d(TAG, "addIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " icon=" + icon);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(mIconSize, mIconSize));
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        if (SPEW) Slog.d(TAG, "updateIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " old=" + old + " icon=" + icon);
        StatusBarIconView view = (StatusBarIconView)mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        if (SPEW) Slog.d(TAG, "removeIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex);
        mStatusIcons.removeViewAt(viewIndex);
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        /* if (DEBUG) */ Slog.d(TAG, "addNotification score=" + notification.score);
        StatusBarIconView iconView = addNotificationViews(key, notification);
        if (iconView == null) return;

        boolean immersive = false;
        try {
            immersive = ActivityManagerNative.getDefault().isTopActivityImmersive();
            if (DEBUG) {
                Slog.d(TAG, "Top activity is " + (immersive?"immersive":"not immersive"));
            }
        } catch (RemoteException ex) {
        }

        /*
         * DISABLED due to missing API
        if (ENABLE_INTRUDERS && (
                   // TODO(dsandler): Only if the screen is on
                notification.notification.intruderView != null)) {
            Slog.d(TAG, "Presenting high-priority notification");
            // special new transient ticker mode
            // 1. Populate mIntruderAlertView

            if (notification.notification.intruderView == null) {
                Slog.e(TAG, notification.notification.toString() + " wanted to intrude but intruderView was null");
                return;
            }

            // bind the click event to the content area
            PendingIntent contentIntent = notification.notification.contentIntent;
            final View.OnClickListener listener = (contentIntent != null)
                    ? new NotificationClicker(contentIntent,
                            notification.pkg, notification.tag, notification.id)
                    : null;

            mIntruderAlertView.applyIntruderContent(notification.notification.intruderView, listener);

            mCurrentlyIntrudingNotification = notification;

            // 2. Animate mIntruderAlertView in
            mHandler.sendEmptyMessage(MSG_SHOW_INTRUDER);

            // 3. Set alarm to age the notification off (TODO)
            mHandler.removeMessages(MSG_HIDE_INTRUDER);
            if (INTRUDER_ALERT_DECAY_MS > 0) {
                mHandler.sendEmptyMessageDelayed(MSG_HIDE_INTRUDER, INTRUDER_ALERT_DECAY_MS);
            }
        } else 
         */
        
        if (notification.notification.fullScreenIntent != null) {
            // not immersive & a full-screen alert should be shown
            Slog.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
            try {
                notification.notification.fullScreenIntent.send();
            } catch (PendingIntent.CanceledException e) {
            }
        } else {
            // usual case: status bar visible & not immersive

            // show the ticker if there isn't an intruder too
            if (mCurrentlyIntrudingNotification == null) {
                tick(null, notification, true);
            }
        }

        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    public void removeNotification(IBinder key) {
        StatusBarNotification old = removeNotificationViews(key);
        if (SPEW) Slog.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            // Cancel the ticker if it's still running
            mTicker.removeEntry(old);

            // Recalculate the position of the sliding windows and the titles.
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

            if (ENABLE_INTRUDERS && old == mCurrentlyIntrudingNotification) {
                mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
            }

            if (CLOSE_PANEL_WHEN_EMPTIED && mNotificationData.size() == 0 && !mAnimating) {
                animateCollapse();
            }
        }

        setAreThereNotifications();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        updateRecentsPanel();
    }

    private void loadNotificationShade() {
        int N = mNotificationData.size();

        ArrayList<View> toShow = new ArrayList<View>();

        for (int i=0; i<N; i++) {
            View row = mNotificationData.get(N-i-1).row;
            toShow.add(row);
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i<mPile.getChildCount(); i++) {
            View child = mPile.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mPile.removeView(remove);
        }

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mPile.addView(v, i);
            }
        }
    }

    private void reloadAllNotificationIcons() {
        if (mNotificationIcons == null) return;
        mNotificationIcons.removeAllViews();
        updateNotificationIcons();
    }

    @Override
    protected void updateNotificationIcons() {
        loadNotificationShade();

        final LinearLayout.LayoutParams params
            = new LinearLayout.LayoutParams(mIconSize + 2*mIconHPadding, mNaturalBarHeight);

        int N = mNotificationData.size();

        if (DEBUG) {
            Slog.d(TAG, "refreshing icons: " + N + " notifications, mNotificationIcons=" + mNotificationIcons);
        }

        ArrayList<View> toShow = new ArrayList<View>();

        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(N-i-1);
            if (ent.notification.score >= HIDE_ICONS_BELOW_SCORE) {
                toShow.add(ent.icon);
            }
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

    @Override
    protected void setAreThereNotifications() {
        final boolean any = mNotificationData.size() > 0;

        final boolean clearable = any && mNotificationData.hasClearableItems();

        if (DEBUG) {
            Slog.d(TAG, "setAreThereNotifications: N=" + mNotificationData.size()
                    + " any=" + any + " clearable=" + clearable);
        }

        if (mClearButton.isShown()) {
            if (clearable != (mClearButton.getAlpha() == 1.0f)) {
                ObjectAnimator.ofFloat(mClearButton, "alpha",
                        clearable ? 1.0f : 0.0f)
                    .setDuration(250)
                    .start();
            }
        } else {
            mClearButton.setAlpha(clearable ? 1.0f : 0.0f);
        }
        mClearButton.setEnabled(clearable);
        
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
            Slog.d(TAG, String.format("disable: 0x%08x -> 0x%08x (diff: 0x%08x)",
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
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) ? "TICKER" : "ticker");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) ? "* " : " ");
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
        flagdbg.append(">");
        Slog.d(TAG, flagdbg.toString());

        if ((diff & StatusBarManager.DISABLE_CLOCK) != 0) {
            boolean show = (state & StatusBarManager.DISABLE_CLOCK) == 0;
            showClock(show);
        }
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state & StatusBarManager.DISABLE_EXPAND) != 0) {
                animateCollapse();
            }
        }

        if ((diff & (StatusBarManager.DISABLE_HOME
                        | StatusBarManager.DISABLE_RECENT
                        | StatusBarManager.DISABLE_BACK)) != 0) {
            // the nav bar will take care of these
            if (mNavigationBarView != null) mNavigationBarView.setDisabledFlags(state);

            if ((state & StatusBarManager.DISABLE_RECENT) != 0) {
                // close recents if it's visible
                mHandler.removeMessages(MSG_CLOSE_RECENTS_PANEL);
                mHandler.sendEmptyMessage(MSG_CLOSE_RECENTS_PANEL);
            }
        }

        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                if (mTicking) {
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                mTicker.halt();
            }
        }
    }

    @Override
    protected BaseStatusBar.H createHandler() {
        return new PhoneStatusBar.H();
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends BaseStatusBar.H {
        public void handleMessage(Message m) {
            super.handleMessage(m);
            switch (m.what) {
                case MSG_OPEN_NOTIFICATION_PANEL:
                    animateExpand();
                    break;
                case MSG_CLOSE_NOTIFICATION_PANEL:
                    animateCollapse();
                    break;
                case MSG_SHOW_INTRUDER:
                    setIntruderAlertVisibility(true);
                    break;
                case MSG_HIDE_INTRUDER:
                    setIntruderAlertVisibility(false);
                    mCurrentlyIntrudingNotification = null;
                    break;
            }
        }
    }

    final Runnable mAnimationCallback = new Runnable() {
        @Override
        public void run() {
            doAnimation(mChoreographer.getFrameTimeNanos());
        }
    };

    final Runnable mRevealAnimationCallback = new Runnable() {
        @Override
        public void run() {
            doRevealAnimation(mChoreographer.getFrameTimeNanos());
        }
    };

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };

    private void makeExpandedVisible() {
        if (SPEW) Slog.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible) {
            return;
        }
        
        mExpandedVisible = true;

        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mStatusBarWindow.getLayoutParams();
        lp.flags &= (~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        final WindowManager wm = WindowManagerImpl.getDefault();
        wm.updateViewLayout(mStatusBarWindow, lp);

        mStatusBarWindow.requestFocus(View.FOCUS_FORWARD);

        visibilityChanged(true);
    }

    public void animateExpand() {
        if (SPEW) Slog.d(TAG, "Animate expand: expanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        prepareTracking(0, true);
        performFling(0, mSelfExpandVelocityPx, true);
    }

    public void animateCollapse() {
        animateCollapse(false);
    }

    public void animateCollapse(boolean excludeRecents) {
        animateCollapse(excludeRecents, 1.0f);
    }

    public void animateCollapse(boolean excludeRecents, float velocityMultiplier) {
        if (SPEW) {
            Slog.d(TAG, "animateCollapse(): mExpanded=" + mExpanded
                    + " mExpandedVisible=" + mExpandedVisible
                    + " mExpanded=" + mExpanded
                    + " mAnimating=" + mAnimating
                    + " mAnimY=" + mAnimY
                    + " mAnimVel=" + mAnimVel);
        }

        if (!excludeRecents) {
            mHandler.removeMessages(MSG_CLOSE_RECENTS_PANEL);
            mHandler.sendEmptyMessage(MSG_CLOSE_RECENTS_PANEL);
        }

        if (!mExpandedVisible) {
            return;
        }

        int y;
        if (mAnimating) {
            y = (int)mAnimY;
        } else {
            y = getExpandedViewMaxHeight()-1;
        }
        // Let the fling think that we're open so it goes in the right direction
        // and doesn't try to re-open the windowshade.
        mExpanded = true;
        prepareTracking(y, false);
        performFling(y, -mSelfCollapseVelocityPx*velocityMultiplier, true);
    }

    void performExpand() {
        if (SPEW) Slog.d(TAG, "performExpand: mExpanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        mExpanded = true;
        makeExpandedVisible();
        updateExpandedViewPos(EXPANDED_FULL_OPEN);

        if (false) postStartTracing();
    }

    void performCollapse() {
        if (SPEW) Slog.d(TAG, "performCollapse: mExpanded=" + mExpanded
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible) {
            return;
        }
        mExpandedVisible = false;
        visibilityChanged(false);
        //mNotificationPanel.setVisibility(View.GONE);

        // Shrink the window to the size of the status bar only
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mStatusBarWindow.getLayoutParams();
        lp.height = getStatusBarHeight();
        lp.flags |= (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        final WindowManager wm = WindowManagerImpl.getDefault();
        wm.updateViewLayout(mStatusBarWindow, lp);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }

        if (!mExpanded) {
            return;
        }
        mExpanded = false;

        // Close any "App info" popups that might have snuck on-screen
        dismissPopups();

        if (mPostCollapseCleanup != null) {
            mPostCollapseCleanup.run();
            mPostCollapseCleanup = null;
        }
    }

    void resetLastAnimTime() {
        mAnimLastTimeNanos = System.nanoTime();
        if (SPEW) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Slog.d(TAG, "resetting last anim time=" + mAnimLastTimeNanos, t);
        }
    }

    void doAnimation(long frameTimeNanos) {
        if (mAnimating) {
            if (SPEW) Slog.d(TAG, "doAnimation dt=" + (frameTimeNanos - mAnimLastTimeNanos));
            if (SPEW) Slog.d(TAG, "doAnimation before mAnimY=" + mAnimY);
            incrementAnim(frameTimeNanos);
            if (SPEW) {
                Slog.d(TAG, "doAnimation after  mAnimY=" + mAnimY);
                Slog.d(TAG, "doAnimation expandedViewMax=" + getExpandedViewMaxHeight());
            }

            if (mAnimY >= getExpandedViewMaxHeight()-1 && !mClosing) {
                if (SPEW) Slog.d(TAG, "Animation completed to expanded state.");
                mAnimating = false;
                updateExpandedViewPos(EXPANDED_FULL_OPEN);
                performExpand();
                return;
            }

            if (mAnimY == 0 && mAnimAccel == 0 && mClosing) {
                if (SPEW) Slog.d(TAG, "Animation completed to collapsed state.");
                mAnimating = false;
                performCollapse();
                return;
            }

            if (mAnimY < getStatusBarHeight() && mClosing) {
                // Draw one more frame with the bar positioned at the top of the screen
                // before ending the animation so that the user sees the bar in
                // its final position.  The call to performCollapse() causes a window
                // relayout which takes time and might cause the animation to skip
                // on the very last frame before the bar disappears if we did it now.
                mAnimY = 0;
                mAnimAccel = 0;
                mAnimVel = 0;
            }

            updateExpandedViewPos((int)mAnimY);
            mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                    mAnimationCallback, null);
        }
    }

    void stopTracking() {
        mTracking = false;
        mPile.setLayerType(View.LAYER_TYPE_NONE, null);
        mVelocityTracker.recycle();
        mVelocityTracker = null;
        mCloseView.setPressed(false);
    }

    void incrementAnim(long frameTimeNanos) {
        final long deltaNanos = Math.max(frameTimeNanos - mAnimLastTimeNanos, 0);
        final float t = deltaNanos * 0.000000001f;                  // ns -> s
        final float y = mAnimY;
        final float v = mAnimVel;                                   // px/s
        final float a = mAnimAccel;                                 // px/s/s
        mAnimY = y + (v*t) + (0.5f*a*t*t);                          // px
        mAnimVel = v + (a*t);                                       // px/s
        mAnimLastTimeNanos = frameTimeNanos;                        // ns
        //Slog.d(TAG, "y=" + y + " v=" + v + " a=" + a + " t=" + t + " mAnimY=" + mAnimY
        //        + " mAnimAccel=" + mAnimAccel);
    }
    
    void doRevealAnimation(long frameTimeNanos) {
        if (SPEW) {
            Slog.d(TAG, "doRevealAnimation: dt=" + (frameTimeNanos - mAnimLastTimeNanos));
        }
        final int h = getCloseViewHeight() + getStatusBarHeight();
        if (mAnimatingReveal && mAnimating && mAnimY < h) {
            incrementAnim(frameTimeNanos);
            if (mAnimY >= h) {
                mAnimY = h;
                updateExpandedViewPos((int)mAnimY);
            } else {
                updateExpandedViewPos((int)mAnimY);
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                        mRevealAnimationCallback, null);
            }
        }
    }

    void prepareTracking(int y, boolean opening) {
        if (CHATTY) {
            Slog.d(TAG, "panel: beginning to track the user's touch, y=" + y + " opening=" + opening);
        }

        mCloseView.setPressed(true);

        mTracking = true;
        mPile.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mVelocityTracker = VelocityTracker.obtain();
        if (opening) {
            mAnimAccel = mExpandAccelPx;
            mAnimVel = mFlingExpandMinVelocityPx;
            mAnimY = getStatusBarHeight();
            updateExpandedViewPos((int)mAnimY);
            mAnimating = true;
            mAnimatingReveal = true;
            resetLastAnimTime();
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                    mAnimationCallback, null);
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                    mRevealAnimationCallback, null);
            mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                    mRevealAnimationCallback, null);
            makeExpandedVisible();
        } else {
            // it's open, close it?
            if (mAnimating) {
                mAnimating = false;
                mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                        mAnimationCallback, null);
            }
            updateExpandedViewPos(y + mViewDelta);
        }
    }

    void performFling(int y, float vel, boolean always) {
        if (CHATTY) {
            Slog.d(TAG, "panel: will fling, y=" + y + " vel=" + vel);
        }

        mAnimatingReveal = false;

        mAnimY = y;
        mAnimVel = vel;

        //Slog.d(TAG, "starting with mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel);

        if (mExpanded) {
            if (!always && (
                    vel > mFlingCollapseMinVelocityPx
                    || (y > (getExpandedViewMaxHeight()*(1f-mCollapseMinDisplayFraction)) &&
                        vel > -mFlingExpandMinVelocityPx))) {
                // We are expanded, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the expanded position.
                mAnimAccel = mExpandAccelPx;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are expanded and are now going to animate away.
                mAnimAccel = -mCollapseAccelPx;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        } else {
            if (always || (
                    vel > mFlingExpandMinVelocityPx
                    || (y > (getExpandedViewMaxHeight()*(1f-mExpandMinDisplayFraction)) &&
                        vel > -mFlingCollapseMinVelocityPx))) {
                // We are collapsed, and they moved enough to allow us to
                // expand.  Animate in the notifications.
                mAnimAccel = mExpandAccelPx;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are collapsed, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the collapsed position.
                mAnimAccel = -mCollapseAccelPx;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        }
        //Slog.d(TAG, "mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel
        //        + " mAnimAccel=" + mAnimAccel);

        resetLastAnimTime();
        mAnimating = true;
        mClosing = mAnimAccel < 0;

        mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                mAnimationCallback, null);
        mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                mRevealAnimationCallback, null);
        mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                mAnimationCallback, null);
        stopTracking();
    }

    boolean interceptTouchEvent(MotionEvent event) {
        if (SPEW) {
            Slog.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled);
        } else if (CHATTY) {
            if (event.getAction() != MotionEvent.ACTION_MOVE) {
                Slog.d(TAG, String.format(
                            "panel: %s at (%f, %f) mDisabled=0x%08x",
                            MotionEvent.actionToString(event.getAction()),
                            event.getRawX(), event.getRawY(), mDisabled));
            }
        }

        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return false;
        }

        final int action = event.getAction();
        final int statusBarSize = getStatusBarHeight();
        final int hitSize = statusBarSize*2;
        final int y = (int)event.getRawY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!areLightsOn()) {
                setLightsOn(true);
            }

            if (!mExpanded) {
                mViewDelta = statusBarSize - y;
            } else {
                mCloseView.getLocationOnScreen(mAbsPos);
                mViewDelta = mAbsPos[1] + statusBarSize + getCloseViewHeight() - y; // XXX: not closeViewHeight, but paddingBottom from the 9patch
            }
            if ((!mExpanded && y < hitSize) ||
                    // @@ add taps outside the panel if it's not full-screen
                    (mExpanded && y > (getExpandedViewMaxHeight()-hitSize))) {

                // We drop events at the edge of the screen to make the windowshade come
                // down by accident less, especially when pushing open a device with a keyboard
                // that rotates (like g1 and droid)
                int x = (int)event.getRawX();
                final int edgeBorder = mEdgeBorder;
                if (x >= edgeBorder && x < mDisplayMetrics.widthPixels - edgeBorder) {
                    prepareTracking(y, !mExpanded);// opening if we're not already fully visible
                    trackMovement(event);
                }
            }
        } else if (mTracking) {
            trackMovement(event);
            final int minY = statusBarSize + getCloseViewHeight();
            if (action == MotionEvent.ACTION_MOVE) {
                if (mAnimatingReveal && (y + mViewDelta) < minY) {
                    // nothing
                } else  {
                    mAnimatingReveal = false;
                    updateExpandedViewPos(y + mViewDelta);
                }
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                mVelocityTracker.computeCurrentVelocity(1000);

                float yVel = mVelocityTracker.getYVelocity();
                boolean negative = yVel < 0;

                float xVel = mVelocityTracker.getXVelocity();
                if (xVel < 0) {
                    xVel = -xVel;
                }
                if (xVel > mFlingGestureMaxXVelocityPx) {
                    xVel = mFlingGestureMaxXVelocityPx; // limit how much we care about the x axis
                }

                float vel = (float)Math.hypot(yVel, xVel);
                if (negative) {
                    vel = -vel;
                }

                if (CHATTY) {
                    Slog.d(TAG, String.format("gesture: vraw=(%f,%f) vnorm=(%f,%f) vlinear=%f",
                        mVelocityTracker.getXVelocity(),
                        mVelocityTracker.getYVelocity(),
                        xVel, yVel,
                        vel));
                }

                performFling(y + mViewDelta, vel, false);
            }

        }
        return false;
    }

    private void trackMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    @Override // CommandQueue
    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;

        if (mNavigationBarView != null) {
            mNavigationBarView.setNavigationIconHints(hints);
        }
    }

    @Override // CommandQueue
    public void setSystemUiVisibility(int vis, int mask) {
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal&~mask) | (vis&mask);
        final int diff = newVal ^ oldVal;

        if (diff != 0) {
            mSystemUiVisibility = newVal;

            if (0 != (diff & View.SYSTEM_UI_FLAG_LOW_PROFILE)) {
                final boolean lightsOut = (0 != (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE));
                if (lightsOut) {
                    animateCollapse();
                    if (mTicking) {
                        mTicker.halt();
                    }
                }
                
                if (mNavigationBarView != null) {
                    mNavigationBarView.setLowProfile(lightsOut);
                }
                
                setStatusBarLowProfile(lightsOut);
            }

            notifyUiVisibilityChanged();
        }
    }

    private void setStatusBarLowProfile(boolean lightsOut) {
        if (mLightsOutAnimation == null) {
            final View notifications = mStatusBarView.findViewById(R.id.notification_icon_area);
            final View systemIcons = mStatusBarView.findViewById(R.id.statusIcons);
            final View signal = mStatusBarView.findViewById(R.id.signal_cluster);
            final View battery = mStatusBarView.findViewById(R.id.battery);
            final View clock = mStatusBarView.findViewById(R.id.clock);

            mLightsOutAnimation = new AnimatorSet();
            mLightsOutAnimation.playTogether(
                    ObjectAnimator.ofFloat(notifications, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(systemIcons, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(signal, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(battery, View.ALPHA, 0.5f),
                    ObjectAnimator.ofFloat(clock, View.ALPHA, 0.5f)
                );
            mLightsOutAnimation.setDuration(750);
            
            mLightsOnAnimation = new AnimatorSet();
            mLightsOnAnimation.playTogether(
                    ObjectAnimator.ofFloat(notifications, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(systemIcons, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(signal, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(battery, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(clock, View.ALPHA, 1)
                );
            mLightsOnAnimation.setDuration(250);
        }
        
        mLightsOutAnimation.cancel();
        mLightsOnAnimation.cancel();

        final Animator a = lightsOut ? mLightsOutAnimation : mLightsOnAnimation;
        a.start();

        setAreThereNotifications();
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

    private void notifyUiVisibilityChanged() {
        try {
            mWindowManager.statusBarVisibilityChanged(mSystemUiVisibility);
        } catch (RemoteException ex) {
        }
    }

    public void topAppWindowChanged(boolean showMenu) {
        if (DEBUG) {
            Slog.d(TAG, (showMenu?"showing":"hiding") + " the MENU button");
        }
        if (mNavigationBarView != null) {
            mNavigationBarView.setMenuVisibility(showMenu);
        }

        // See above re: lights-out policy for legacy apps.
        if (showMenu) setLightsOn(true);
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition) {
        boolean altBack = (backDisposition == InputMethodService.BACK_DISPOSITION_WILL_DISMISS)
            || ((vis & InputMethodService.IME_VISIBLE) != 0);

        mCommandQueue.setNavigationIconHints(
                altBack ? (mNavigationIconHints | StatusBarManager.NAVIGATION_HINT_BACK_ALT)
                        : (mNavigationIconHints & ~StatusBarManager.NAVIGATION_HINT_BACK_ALT));
    }

    @Override
    public void setHardKeyboardStatus(boolean available, boolean enabled) { }

    private class NotificationClicker implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;

        NotificationClicker(PendingIntent intent, String pkg, String tag, int id) {
            mIntent = intent;
            mPkg = pkg;
            mTag = tag;
            mId = id;
        }

        public void onClick(View v) {
            try {
                // The intent we are sending is for the application, which
                // won't have permission to immediately start an activity after
                // the user switches to home.  We know it is safe to do at this
                // point, so make sure new activity switches are now allowed.
                ActivityManagerNative.getDefault().resumeAppSwitches();
                // Also, notifications can be launched from the lock screen,
                // so dismiss the lock screen when the activity starts.
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
            }

            if (mIntent != null) {
                int[] pos = new int[2];
                v.getLocationOnScreen(pos);
                Intent overlay = new Intent();
                overlay.setSourceBounds(
                        new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
                try {
                    mIntent.send(mContext, 0, overlay);
                } catch (PendingIntent.CanceledException e) {
                    // the stack trace isn't very helpful here.  Just log the exception message.
                    Slog.w(TAG, "Sending contentIntent failed: " + e);
                }

                KeyguardManager kgm =
                    (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                if (kgm != null) kgm.exitKeyguardSecurely(null);
            }

            try {
                mBarService.onNotificationClick(mPkg, mTag, mId);
            } catch (RemoteException ex) {
                // system process is dead if we're here.
            }

            // close the shade if it was open
            animateCollapse();

            // If this click was on the intruder alert, hide that instead
            mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
        }
    }

    @Override
    protected void tick(IBinder key, StatusBarNotification n, boolean firstTime) {
        // no ticking in lights-out mode
        if (!areLightsOn()) return;
        
        // Show the ticker if one is requested. Also don't do this
        // until status bar window is attached to the window manager,
        // because...  well, what's the point otherwise?  And trying to
        // run a ticker without being attached will crash!
        if (n.notification.tickerText != null && mStatusBarWindow.getWindowToken() != null) {
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
            mIcons.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
        }

        @Override
        public void tickerDone() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
        }

        public void tickerHalting() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.fade_out,
                        mTickingDoneListener));
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
            pw.println("  mExpanded=" + mExpanded
                    + ", mExpandedVisible=" + mExpandedVisible);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mAnimating=" + mAnimating
                    + ", mAnimY=" + mAnimY + ", mAnimVel=" + mAnimVel
                    + ", mAnimAccel=" + mAnimAccel);
            pw.println("  mAnimLastTimeNanos=" + mAnimLastTimeNanos);
            pw.println("  mAnimatingReveal=" + mAnimatingReveal
                    + " mViewDelta=" + mViewDelta);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mPile: " + viewInfo(mPile));
            pw.println("  mCloseView: " + viewInfo(mCloseView));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
        }

        pw.print("  mNavigationBarView=");
        if (mNavigationBarView == null) {
            pw.println("null");
        } else {
            mNavigationBarView.dump(fd, pw, args);
        }

        if (DUMPTRUCK) {
            synchronized (mNotificationData) {
                int N = mNotificationData.size();
                pw.println("  notification icons: " + N);
                for (int i=0; i<N; i++) {
                    NotificationData.Entry e = mNotificationData.get(i);
                    pw.println("    [" + i + "] key=" + e.key + " icon=" + e.icon);
                    StatusBarNotification n = e.notification;
                    pw.println("         pkg=" + n.pkg + " id=" + n.id + " score=" + n.score);
                    pw.println("         notification=" + n.notification);
                    pw.println("         tickerText=\"" + n.notification.tickerText + "\"");
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
                            Slog.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                    + ") " + mStatusBarView.getWidth() + "x"
                                    + getStatusBarHeight());
                            mStatusBarView.debug();
                        }
                    });
            }
        }

        mNetworkController.dump(fd, pw, args);
    }

    @Override
    public void createAndAddWindows() {
        addStatusBarWindow();
    }

    private void addStatusBarWindow() {
        // Put up the view
        final int height = getStatusBarHeight();

        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);

        if (ActivityManager.isHighEndGfx(mDisplay)) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.gravity = getStatusBarGravity();
        lp.setTitle("StatusBar");
        lp.packageName = mContext.getPackageName();

        makeStatusBarView();
        WindowManagerImpl.getDefault().addView(mStatusBarWindow, lp);
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    void updateExpandedInvisiblePosition() {
        mTrackingPosition = -mDisplayMetrics.heightPixels;
    }

    static final float saturate(float a) {
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    @Override
    protected int getExpandedViewMaxHeight() {
        return mDisplayMetrics.heightPixels - mNotificationPanelMarginBottomPx;
    }

    @Override
    protected void updateExpandedViewPos(int expandedPosition) {
        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos before expandedPosition=" + expandedPosition
                    //+ " mTrackingParams.y=" + ((mTrackingParams == null) ? "?" : mTrackingParams.y)
                    + " mTrackingPosition=" + mTrackingPosition
                    + " gravity=" + mNotificationPanelGravity);
        }

        int panelh = 0;
        final boolean portrait = mDisplayMetrics.heightPixels > mDisplayMetrics.widthPixels;
        
        final int disph = getExpandedViewMaxHeight();

        // If the expanded view is not visible, make sure they're still off screen.
        // Maybe the view was resized.
        if (!mExpandedVisible) {
            updateExpandedInvisiblePosition();
            return;
        }

        // tracking view...
        int pos;
        if (expandedPosition == EXPANDED_FULL_OPEN) {
            panelh = disph;
        }
        else if (expandedPosition == EXPANDED_LEAVE_ALONE) {
            panelh = mTrackingPosition;
        }
        else {
            if (expandedPosition <= disph) {
                panelh = expandedPosition;
            } else {
                panelh = disph;
            }
        }

        // catch orientation changes and other peculiar cases
        if (panelh > disph || (panelh < disph && !mTracking && !mAnimating)) {
            panelh = disph;
        } else if (panelh < 0) {
            panelh = 0;
        }

        mTrackingPosition = panelh;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mNotificationPanel.getLayoutParams();
        lp.height = panelh;
        lp.gravity = mNotificationPanelGravity;
        lp.leftMargin = mNotificationPanelMarginLeftPx;
        if (SPEW) {
            Slog.v(TAG, "updated cropView height=" + panelh + " grav=" + lp.gravity);
        }
        mNotificationPanel.setLayoutParams(lp);

        if (DIM_BEHIND_EXPANDED_PANEL && ActivityManager.isHighEndGfx(mDisplay)) {
            // woo, special effects
            final int barh = getCloseViewHeight() + getStatusBarHeight();
            final float frac = saturate((float)(panelh - barh) / (disph - barh));
            final int color = ((int)(0xB0 * Math.sin(frac * 1.57f))) << 24;
            mStatusBarWindow.setBackgroundColor(color);
        }
    }

    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
    }

    void performDisableActions(int net) {
        int old = mDisabled;
        int diff = net ^ old;
        mDisabled = net;

        // act accordingly
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((net & StatusBarManager.DISABLE_EXPAND) != 0) {
                Slog.d(TAG, "DISABLE_EXPAND: yes");
                animateCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((net & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    mNotificationIcons.setVisibility(View.INVISIBLE);
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (net & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                mTicker.halt();
            }
        }
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        final int mini(int a, int b) {
            return (b>a?a:b);
        }
        public void onClick(View v) {
            synchronized (mNotificationData) {
                // animate-swipe all dismissable notifications, then animate the shade closed
                int numChildren = mPile.getChildCount();

                int scrollTop = mScrollView.getScrollY();
                int scrollBottom = scrollTop + mScrollView.getHeight();
                final ArrayList<View> snapshot = new ArrayList<View>(numChildren);
                for (int i=0; i<numChildren; i++) {
                    final View child = mPile.getChildAt(i);
                    if (mPile.canChildBeDismissed(child) && child.getBottom() > scrollTop &&
                            child.getTop() < scrollBottom) {
                        snapshot.add(child);
                    }
                }
                if (snapshot.isEmpty()) {
                    animateCollapse(false);
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // Decrease the delay for every row we animate to give the sense of
                        // accelerating the swipes
                        final int ROW_DELAY_DECREMENT = 10;
                        int currentDelay = 140;
                        int totalDelay = 0;

                        // Set the shade-animating state to avoid doing other work during
                        // all of these animations. In particular, avoid layout and
                        // redrawing when collapsing the shade.
                        mPile.setViewRemoval(false);

                        mPostCollapseCleanup = new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    mPile.setViewRemoval(true);
                                    mBarService.onClearAllNotifications();
                                } catch (Exception ex) { }
                            }
                        };

                        View sampleView = snapshot.get(0);
                        int width = sampleView.getWidth();
                        final int velocity = width * 8; // 1000/8 = 125 ms duration
                        for (final View _v : snapshot) {
                            mHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mPile.dismissRowAnimated(_v, velocity);
                                }
                            }, totalDelay);
                            currentDelay = Math.max(50, currentDelay - ROW_DELAY_DECREMENT);
                            totalDelay += currentDelay;
                        }
                        // Delay the collapse animation until after all swipe animations have
                        // finished. Provide some buffer because there may be some extra delay
                        // before actually starting each swipe animation. Ideally, we'd
                        // synchronize the end of those animations with the start of the collaps
                        // exactly.
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                animateCollapse(false);
                            }
                        }, totalDelay + 225);
                    }
                }).start();
            }
        }
    };

    private View.OnClickListener mSettingsButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                // Dismiss the lock screen when Settings starts.
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
            }
            v.getContext().startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            animateCollapse();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                boolean excludeRecents = false;
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    String reason = intent.getStringExtra("reason");
                    if (reason != null) {
                        excludeRecents = reason.equals("recentapps");
                    }
                }
                animateCollapse(excludeRecents);
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
                repositionNavigationBar();
                updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
            }
        }
    };

    private void setIntruderAlertVisibility(boolean vis) {
        if (!ENABLE_INTRUDERS) return;
        if (DEBUG) {
            Slog.v(TAG, (vis ? "showing" : "hiding") + " intruder alert window");
        }
        mIntruderAlertView.setVisibility(vis ? View.VISIBLE : View.GONE);
    }

    public void dismissIntruder() {
        if (mCurrentlyIntrudingNotification == null) return;

        try {
            mBarService.onNotificationClear(
                    mCurrentlyIntrudingNotification.pkg,
                    mCurrentlyIntrudingNotification.tag, 
                    mCurrentlyIntrudingNotification.id);
        } catch (android.os.RemoteException ex) {
            // oh well
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
        final Context context = mContext;
        final Resources res = context.getResources();

        if (mClearButton instanceof TextView) {
            ((TextView)mClearButton).setText(context.getText(R.string.status_bar_clear_all_button));
        }
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
//            Slog.d(TAG, "size=" + newIconSize + " padding=" + newIconHPadding);
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
        
        mNotificationPanelMarginBottomPx
            = (int) res.getDimension(R.dimen.notification_panel_margin_bottom);
        mNotificationPanelMarginLeftPx
            = (int) res.getDimension(R.dimen.notification_panel_margin_left);
        mNotificationPanelGravity = res.getInteger(R.integer.notification_panel_layout_gravity);
        if (mNotificationPanelGravity <= 0) {
            mNotificationPanelGravity = Gravity.CENTER_VERTICAL | Gravity.TOP; 
        }

        if (false) Slog.v(TAG, "updateResources");
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
        vib.vibrate(250);
    }

    Runnable mStartTracing = new Runnable() {
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Slog.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        public void run() {
            android.os.Debug.stopMethodTracing();
            Slog.d(TAG, "stopTracing");
            vibrate();
        }
    };

    @Override
    protected void haltTicker() {
        mTicker.halt();
    }

    @Override
    protected boolean shouldDisableNavbarGestures() {
        return mExpanded || (mDisabled & StatusBarManager.DISABLE_HOME) != 0;
    }
}

