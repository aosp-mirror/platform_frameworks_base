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

package com.android.policy.statusbar.phone;

import com.android.internal.util.CharSequences;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarNotification;

import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Telephony;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;


public class PhoneStatusBarService extends StatusBarService {
    static final String TAG = "PhoneStatusBarService";
    static final boolean SPEW = false;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    static final int EXPANDED_LEAVE_ALONE = -10000;
    static final int EXPANDED_FULL_OPEN = -10001;

    private static final int MSG_ANIMATE = 1000;
    private static final int MSG_ANIMATE_REVEAL = 1001;

    public interface NotificationCallbacks {
        void onSetDisabled(int status);
        void onClearAll();
        void onNotificationClick(String pkg, String tag, int id);
        void onPanelRevealed();
    }

    private class ExpandedDialog extends Dialog {
        ExpandedDialog(Context context) {
            super(context, com.android.internal.R.style.Theme_Light_NoTitleBar);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    //TODO PhoneStatusBarService.this.collapse();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
    
    int mHeight;
    int mIconWidth;

    Display mDisplay;
    StatusBarView mStatusBarView;
    int mPixelFormat;
    H mHandler = new H();
    Object mQueueLock = new Object();
    NotificationCallbacks mNotificationCallbacks;
    
    // icons
    String[] mRightIconSlots;
    LinearLayout mIcons;
    IconMerger mNotificationIcons;
    LinearLayout mStatusIcons;

    // expanded notifications
    Dialog mExpandedDialog;
    ExpandedView mExpandedView;
    WindowManager.LayoutParams mExpandedParams;
    ScrollView mScrollView;
    View mNotificationLinearLayout;
    View mExpandedContents;
    // top bar
    TextView mNoNotificationsTitle;
    TextView mSpnLabel;
    TextView mPlmnLabel;
    TextView mClearButton;
    // drag bar
    CloseDragHandle mCloseView;
    // ongoing
    NotificationData mOngoing = new NotificationData();
    TextView mOngoingTitle;
    LinearLayout mOngoingItems;
    // latest
    NotificationData mLatest = new NotificationData();
    TextView mLatestTitle;
    LinearLayout mLatestItems;
    // position
    int[] mPositionTmp = new int[2];
    boolean mExpanded;
    boolean mExpandedVisible;

    // the date view
    DateView mDateView;

    // the tracker view
    TrackingView mTrackingView;
    WindowManager.LayoutParams mTrackingParams;
    int mTrackingPosition; // the position of the top of the tracking view.

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;
    
    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;
    
    static final int ANIM_FRAME_DURATION = (1000/60);
    
    boolean mAnimating;
    long mCurAnimationTime;
    float mDisplayHeight;
    float mAnimY;
    float mAnimVel;
    float mAnimAccel;
    long mAnimLastTime;
    boolean mAnimatingReveal = false;
    int mViewDelta;
    int[] mAbsPos = new int[2];
    
    // for disabling the status bar
    int mDisabled = 0;

    /**
     * Construct the service, add the status bar view to the window manager
     */
    @Override
    public void onCreate() {
        // First set up our views and stuff.
        mDisplay = ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        makeStatusBarView(this);

        // Next, call super.onCreate(), which will populate our views.
        super.onCreate();
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    private void makeStatusBarView(Context context) {
        Resources res = context.getResources();
        mRightIconSlots = res.getStringArray(R.array.status_bar_icon_order);

        mHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mIconWidth = mHeight;

        ExpandedView expanded = (ExpandedView)View.inflate(context,
                R.layout.status_bar_expanded, null);
        expanded.mService = this;
        StatusBarView sb = (StatusBarView)View.inflate(context, R.layout.status_bar, null);
        sb.mService = this;

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.TRANSLUCENT;
        Drawable bg = sb.getBackground();
        if (bg != null) {
            mPixelFormat = bg.getOpacity();
        }

        mStatusBarView = sb;
        mStatusIcons = (LinearLayout)sb.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)sb.findViewById(R.id.notificationIcons);
        mNotificationIcons.service = this;
        mIcons = (LinearLayout)sb.findViewById(R.id.icons);
        mTickerView = sb.findViewById(R.id.ticker);
        mDateView = (DateView)sb.findViewById(R.id.date);

        mExpandedDialog = new ExpandedDialog(context);
        mExpandedView = expanded;
        mExpandedContents = expanded.findViewById(R.id.notificationLinearLayout);
        mOngoingTitle = (TextView)expanded.findViewById(R.id.ongoingTitle);
        mOngoingItems = (LinearLayout)expanded.findViewById(R.id.ongoingItems);
        mLatestTitle = (TextView)expanded.findViewById(R.id.latestTitle);
        mLatestItems = (LinearLayout)expanded.findViewById(R.id.latestItems);
        mNoNotificationsTitle = (TextView)expanded.findViewById(R.id.noNotificationsTitle);
        mClearButton = (TextView)expanded.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mSpnLabel = (TextView)expanded.findViewById(R.id.spnLabel);
        mPlmnLabel = (TextView)expanded.findViewById(R.id.plmnLabel);
        mScrollView = (ScrollView)expanded.findViewById(R.id.scroll);
        mNotificationLinearLayout = expanded.findViewById(R.id.notificationLinearLayout);

        mOngoingTitle.setVisibility(View.GONE);
        mLatestTitle.setVisibility(View.GONE);
        
        mTicker = new MyTicker(context, sb);

        TickerView tickerView = (TickerView)sb.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mTrackingView = (TrackingView)View.inflate(context, R.layout.status_bar_tracking, null);
        mTrackingView.mService = this;
        mCloseView = (CloseDragHandle)mTrackingView.findViewById(R.id.close);
        mCloseView.mService = this;

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();
        mDateView.setVisibility(View.INVISIBLE);

        // before we register for broadcasts
        mPlmnLabel.setText(com.android.internal.R.string.lockscreen_carrier_default);
        mPlmnLabel.setVisibility(View.VISIBLE);
        mSpnLabel.setText("");
        mSpnLabel.setVisibility(View.GONE);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    protected void addStatusBarView() {
        final StatusBarView view = mStatusBarView;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mHeight,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
                PixelFormat.RGBX_8888);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBar");
        // TODO lp.windowAnimations = R.style.Animation_StatusBar;

        WindowManagerImpl.getDefault().addView(view, lp);
    }

    // ================================================================================
    // Always called from the UI thread.
    // ================================================================================

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        Slog.d(TAG, "addIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " icon=" + icon);
        StatusBarIconView view = new StatusBarIconView(this, slot);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(mIconWidth, mHeight));
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        Slog.d(TAG, "updateIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " old=" + old + " icon=" + icon);
        StatusBarIconView view = (StatusBarIconView)mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        Slog.d(TAG, "removeIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex);
        mStatusIcons.removeViewAt(viewIndex);
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        NotificationData list;
        ViewGroup parent;
        final boolean isOngoing = notification.isOngoing();
        if (isOngoing) {
            list = mOngoing;
            parent = mOngoingItems;
        } else {
            list = mLatest;
            parent = mLatestItems;
        }
        // Construct the expanded view.
        final View view = makeNotificationView(notification, parent);
        // Construct the icon.
        StatusBarIconView iconView = new StatusBarIconView(this,
                notification.pkg + "/" + notification.id);
        iconView.set(new StatusBarIcon(notification.pkg, notification.notification.icon,
                    notification.notification.iconLevel));
        // Add the expanded view.
        final int viewIndex = list.add(key, notification, view);
        parent.addView(view, viewIndex);
        // Add the icon.
        final int iconIndex = chooseIconIndex(isOngoing, viewIndex);
        mNotificationIcons.addView(iconView, iconIndex,
                new LinearLayout.LayoutParams(mIconWidth, mHeight));

        // show the ticker
        // TODO

        // recalculate the position of the sliding windows
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
    }

    public void removeNotification(IBinder key) {
    }

    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    public void disable(int state) {
        final int old = mDisabled;
        final int diff = state ^ old;
        mDisabled = state;

        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state & StatusBarManager.DISABLE_EXPAND) != 0) {
                Slog.d(TAG, "DISABLE_EXPAND: yes");
                animateCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
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
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_TICKER: yes");
                mTicker.halt();
            }
        }
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_ANIMATE:
                    doAnimation();
                    break;
                case MSG_ANIMATE_REVEAL:
                    doRevealAnimation();
                    break;
            }
        }
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };

    private int chooseIconIndex(boolean isOngoing, int index) {
        final int ongoingSize = mOngoing.size();
        final int latestSize = mLatest.size();
        if (!isOngoing) {
            index = mLatest.size() + index;
        }
        return (ongoingSize + latestSize) - index - 1;
    }
    
    View makeNotificationView(StatusBarNotification notification, ViewGroup parent) {
        Notification n = notification.notification;
        RemoteViews remoteViews = n.contentView;
        if (remoteViews == null) {
            return null;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(com.android.internal.R.layout.status_bar_latest_event,
                parent, false);

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(com.android.internal.R.id.content);
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.setOnFocusChangeListener(mFocusChangeListener);
        PendingIntent contentIntent = n.contentIntent;
        if (contentIntent != null) {
            content.setOnClickListener(new Launcher(contentIntent, notification.pkg,
                        notification.tag, notification.id));
        }

        View child = null;
        Exception exception = null;
        try {
            child = remoteViews.apply(this, content);
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (child == null) {
            Slog.e(TAG, "couldn't inflate view for package " + notification.pkg, exception);
            return null;
        }
        content.addView(child);

        row.setDrawingCacheEnabled(true);

        /*
        notification.view = row;
        notification.contentView = child;
        */

        return row;
    }

    /*
                StatusBarIcon icon = new StatusBarIcon(pkg, notification.icon,
                        notification.iconLevel);
                icon.number = notification.number;
    */     
    /*
    void addNotificationView(StatusBarNotification notification) {
        if (notification.view != null) {
            throw new RuntimeException("Assertion failed: notification.view="
                    + notification.view);
        }

        LinearLayout parent = notification.data.ongoingEvent ? mOngoingItems : mLatestItems;

        View child = makeNotificationView(notification, parent);
        if (child == null) {
            return ;
        }

        int index = mNotificationData.getExpandedIndex(notification);
        parent.addView(child, index);
    }
    */

    /**
     * Remove the old one and put the new one in its place.
     * @param notification the notification
     */
    /*
    void updateNotificationView(StatusBarNotification notification, NotificationData oldData) {
        NotificationData n = notification.data;
        if (oldData != null && n != null
                && n.when == oldData.when
                && n.ongoingEvent == oldData.ongoingEvent
                && n.contentView != null && oldData.contentView != null
                && n.contentView.getPackage() != null
                && oldData.contentView.getPackage() != null
                && oldData.contentView.getPackage().equals(n.contentView.getPackage())
                && oldData.contentView.getLayoutId() == n.contentView.getLayoutId()
                && notification.view != null) {
            mNotificationData.update(notification);
            try {
                n.contentView.reapply(this, notification.contentView);

                // update the contentIntent
                ViewGroup content = (ViewGroup)notification.view.findViewById(
                        com.android.internal.R.id.content);
                PendingIntent contentIntent = n.contentIntent;
                if (contentIntent != null) {
                    content.setOnClickListener(new Launcher(contentIntent, n.pkg, n.tag, n.id));
                }
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Slog.w(TAG, "couldn't reapply views for package " + n.contentView.getPackage(), e);
                removeNotificationView(notification);
            }
        } else {
            mNotificationData.update(notification);
            removeNotificationView(notification);
            addNotificationView(notification);
        }
        setAreThereNotifications();
    }

    void removeNotificationView(StatusBarNotification notification) {
        View v = notification.view;
        if (v != null) {
            ViewGroup parent = (ViewGroup)v.getParent();
            parent.removeView(v);
            notification.view = null;
        }
    }
    */

    private void setAreThereNotifications() {
    /*
        boolean ongoing = mOngoingItems.getChildCount() != 0;
        boolean latest = mLatestItems.getChildCount() != 0;

        if (mNotificationData.hasClearableItems()) {
            mClearButton.setVisibility(View.VISIBLE);
        } else {
            mClearButton.setVisibility(View.INVISIBLE);
        }

        mOngoingTitle.setVisibility(ongoing ? View.VISIBLE : View.GONE);
        mLatestTitle.setVisibility(latest ? View.VISIBLE : View.GONE);

        if (ongoing || latest) {
            mNoNotificationsTitle.setVisibility(View.GONE);
        } else {
            mNoNotificationsTitle.setVisibility(View.VISIBLE);
        }
    */
    }

    private void makeExpandedVisible() {
        if (SPEW) Slog.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible) {
            return;
        }
        mExpandedVisible = true;
        visibilityChanged(true);
        
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mExpandedView.requestFocus(View.FOCUS_FORWARD);
        mTrackingView.setVisibility(View.VISIBLE);
        
        if (!mTicking) {
            setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
        }
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
        performFling(0, 2000.0f, true);
    }
    
    public void animateCollapse() {
        if (SPEW) {
            Slog.d(TAG, "animateCollapse(): mExpanded=" + mExpanded
                    + " mExpandedVisible=" + mExpandedVisible
                    + " mExpanded=" + mExpanded
                    + " mAnimating=" + mAnimating
                    + " mAnimY=" + mAnimY
                    + " mAnimVel=" + mAnimVel);
        }
        
        if (!mExpandedVisible) {
            return;
        }

        int y;
        if (mAnimating) {
            y = (int)mAnimY;
        } else {
            y = mDisplay.getHeight()-1;
        }
        // Let the fling think that we're open so it goes in the right direction
        // and doesn't try to re-open the windowshade.
        mExpanded = true;
        prepareTracking(y, false);
        performFling(y, -2000.0f, true);
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
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mTrackingView.setVisibility(View.GONE);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }
        setDateViewVisibility(false, com.android.internal.R.anim.fade_out);
        
        if (!mExpanded) {
            return;
        }
        mExpanded = false;
    }

    void doAnimation() {
        if (mAnimating) {
            if (SPEW) Slog.d(TAG, "doAnimation");
            if (SPEW) Slog.d(TAG, "doAnimation before mAnimY=" + mAnimY);
            incrementAnim();
            if (SPEW) Slog.d(TAG, "doAnimation after  mAnimY=" + mAnimY);
            if (mAnimY >= mDisplay.getHeight()-1) {
                if (SPEW) Slog.d(TAG, "Animation completed to expanded state.");
                mAnimating = false;
                updateExpandedViewPos(EXPANDED_FULL_OPEN);
                performExpand();
            }
            else if (mAnimY < mStatusBarView.getHeight()) {
                if (SPEW) Slog.d(TAG, "Animation completed to collapsed state.");
                mAnimating = false;
                updateExpandedViewPos(0);
                performCollapse();
            }
            else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
            }
        }
    }

    void stopTracking() {
        mTracking = false;
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    void incrementAnim() {
        long now = SystemClock.uptimeMillis();
        float t = ((float)(now - mAnimLastTime)) / 1000;            // ms -> s
        final float y = mAnimY;
        final float v = mAnimVel;                                   // px/s
        final float a = mAnimAccel;                                 // px/s/s
        mAnimY = y + (v*t) + (0.5f*a*t*t);                          // px
        mAnimVel = v + (a*t);                                       // px/s
        mAnimLastTime = now;                                        // ms
        //Slog.d(TAG, "y=" + y + " v=" + v + " a=" + a + " t=" + t + " mAnimY=" + mAnimY
        //        + " mAnimAccel=" + mAnimAccel);
    }

    void doRevealAnimation() {
        final int h = mCloseView.getHeight() + mStatusBarView.getHeight();
        if (mAnimatingReveal && mAnimating && mAnimY < h) {
            incrementAnim();
            if (mAnimY >= h) {
                mAnimY = h;
                updateExpandedViewPos((int)mAnimY);
            } else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                        mCurAnimationTime);
            }
        }
    }
    
    void prepareTracking(int y, boolean opening) {
        mTracking = true;
        mVelocityTracker = VelocityTracker.obtain();
        if (opening) {
            mAnimAccel = 2000.0f;
            mAnimVel = 200;
            mAnimY = mStatusBarView.getHeight();
            updateExpandedViewPos((int)mAnimY);
            mAnimating = true;
            mAnimatingReveal = true;
            mHandler.removeMessages(MSG_ANIMATE);
            mHandler.removeMessages(MSG_ANIMATE_REVEAL);
            long now = SystemClock.uptimeMillis();
            mAnimLastTime = now;
            mCurAnimationTime = now + ANIM_FRAME_DURATION;
            mAnimating = true;
            mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                    mCurAnimationTime);
            makeExpandedVisible();
        } else {
            // it's open, close it?
            if (mAnimating) {
                mAnimating = false;
                mHandler.removeMessages(MSG_ANIMATE);
            }
            updateExpandedViewPos(y + mViewDelta);
        }
    }
    
    void performFling(int y, float vel, boolean always) {
        mAnimatingReveal = false;
        mDisplayHeight = mDisplay.getHeight();

        mAnimY = y;
        mAnimVel = vel;

        //Slog.d(TAG, "starting with mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel);

        if (mExpanded) {
            if (!always && (
                    vel > 200.0f
                    || (y > (mDisplayHeight-25) && vel > -200.0f))) {
                // We are expanded, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the expanded position.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are expanded and are now going to animate away.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        } else {
            if (always || (
                    vel > 200.0f
                    || (y > (mDisplayHeight/2) && vel > -200.0f))) {
                // We are collapsed, and they moved enough to allow us to
                // expand.  Animate in the notifications.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are collapsed, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the collapsed position.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        }
        //Slog.d(TAG, "mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel
        //        + " mAnimAccel=" + mAnimAccel);

        long now = SystemClock.uptimeMillis();
        mAnimLastTime = now;
        mCurAnimationTime = now + ANIM_FRAME_DURATION;
        mAnimating = true;
        mHandler.removeMessages(MSG_ANIMATE);
        mHandler.removeMessages(MSG_ANIMATE_REVEAL);
        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
        stopTracking();
    }
    
    boolean interceptTouchEvent(MotionEvent event) {
        if (SPEW) {
            Slog.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled);
        }

        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return false;
        }
        
        final int statusBarSize = mStatusBarView.getHeight();
        final int hitSize = statusBarSize*2;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final int y = (int)event.getRawY();

            if (!mExpanded) {
                mViewDelta = statusBarSize - y;
            } else {
                mTrackingView.getLocationOnScreen(mAbsPos);
                mViewDelta = mAbsPos[1] + mTrackingView.getHeight() - y;
            }
            if ((!mExpanded && y < hitSize) ||
                    (mExpanded && y > (mDisplay.getHeight()-hitSize))) {

                // We drop events at the edge of the screen to make the windowshade come
                // down by accident less, especially when pushing open a device with a keyboard
                // that rotates (like g1 and droid)
                int x = (int)event.getRawX();
                final int edgeBorder = mEdgeBorder;
                if (x >= edgeBorder && x < mDisplay.getWidth() - edgeBorder) {
                    prepareTracking(y, !mExpanded);// opening if we're not already fully visible
                    mVelocityTracker.addMovement(event);
                }
            }
        } else if (mTracking) {
            mVelocityTracker.addMovement(event);
            final int minY = statusBarSize + mCloseView.getHeight();
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int y = (int)event.getRawY();
                if (mAnimatingReveal && y < minY) {
                    // nothing
                } else  {
                    mAnimatingReveal = false;
                    updateExpandedViewPos(y + mViewDelta);
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                mVelocityTracker.computeCurrentVelocity(1000);

                float yVel = mVelocityTracker.getYVelocity();
                boolean negative = yVel < 0;

                float xVel = mVelocityTracker.getXVelocity();
                if (xVel < 0) {
                    xVel = -xVel;
                }
                if (xVel > 150.0f) {
                    xVel = 150.0f; // limit how much we care about the x axis
                }

                float vel = (float)Math.hypot(yVel, xVel);
                if (negative) {
                    vel = -vel;
                }
                
                performFling((int)event.getRawY(), vel, false);
            }
            
        }
        return false;
    }

    private class Launcher implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;

        Launcher(PendingIntent intent, String pkg, String tag, int id) {
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
            } catch (RemoteException e) {
            }
            int[] pos = new int[2];
            v.getLocationOnScreen(pos);
            Intent overlay = new Intent();
            overlay.setSourceBounds(
                    new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
            try {
                mIntent.send(PhoneStatusBarService.this, 0, overlay);
                mNotificationCallbacks.onNotificationClick(mPkg, mTag, mId);
            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.  Just log the exception message.
                Slog.w(TAG, "Sending contentIntent failed: " + e);
            }
            //collapse();
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, StatusBarView sb) {
            super(context, sb);
        }
        
        @Override
        void tickerStarting() {
            mTicking = true;
            mIcons.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
            if (mExpandedVisible) {
                setDateViewVisibility(false, com.android.internal.R.anim.push_up_out);
            }
        }

        @Override
        void tickerDone() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
            if (mExpandedVisible) {
                setDateViewVisibility(true, com.android.internal.R.anim.push_down_in);
            }
        }

        void tickerHalting() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.fade_out,
                        mTickingDoneListener));
            if (mExpandedVisible) {
                setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
            }
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
        Animation anim = AnimationUtils.loadAnimation(PhoneStatusBarService.this, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }

    public String viewInfo(View v) {
        return "(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + " " + v.getWidth() + "x" + v.getHeight() + ")";
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump StatusBar from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpanded=" + mExpanded
                    + ", mExpandedVisible=" + mExpandedVisible);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mAnimating=" + mAnimating
                    + ", mAnimY=" + mAnimY + ", mAnimVel=" + mAnimVel
                    + ", mAnimAccel=" + mAnimAccel);
            pw.println("  mCurAnimationTime=" + mCurAnimationTime
                    + " mAnimLastTime=" + mAnimLastTime);
            pw.println("  mDisplayHeight=" + mDisplayHeight
                    + " mAnimatingReveal=" + mAnimatingReveal
                    + " mViewDelta=" + mViewDelta);
            pw.println("  mDisplayHeight=" + mDisplayHeight);
            pw.println("  mExpandedParams: " + mExpandedParams);
            pw.println("  mExpandedView: " + viewInfo(mExpandedView));
            pw.println("  mExpandedDialog: " + mExpandedDialog);
            pw.println("  mTrackingParams: " + mTrackingParams);
            pw.println("  mTrackingView: " + viewInfo(mTrackingView));
            pw.println("  mOngoingTitle: " + viewInfo(mOngoingTitle));
            pw.println("  mOngoingItems: " + viewInfo(mOngoingItems));
            pw.println("  mLatestTitle: " + viewInfo(mLatestTitle));
            pw.println("  mLatestItems: " + viewInfo(mLatestItems));
            pw.println("  mNoNotificationsTitle: " + viewInfo(mNoNotificationsTitle));
            pw.println("  mCloseView: " + viewInfo(mCloseView));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
            pw.println("mNotificationLinearLayout: " + viewInfo(mNotificationLinearLayout));
        }
        /*
        synchronized (mNotificationData) {
            int N = mNotificationData.ongoingCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getOngoing(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
            N = mNotificationData.latestCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getLatest(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
        }
        */
        
        if (false) {
            pw.println("see the logcat for a dump of the views we have created.");
            // must happen on ui thread
            mHandler.post(new Runnable() {
                    public void run() {
                        mStatusBarView.getLocationOnScreen(mAbsPos);
                        Slog.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mStatusBarView.getWidth() + "x"
                                + mStatusBarView.getHeight());
                        mStatusBarView.debug();

                        mExpandedView.getLocationOnScreen(mAbsPos);
                        Slog.d(TAG, "mExpandedView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mExpandedView.getWidth() + "x"
                                + mExpandedView.getHeight());
                        mExpandedView.debug();

                        mTrackingView.getLocationOnScreen(mAbsPos);
                        Slog.d(TAG, "mTrackingView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mTrackingView.getWidth() + "x"
                                + mTrackingView.getHeight());
                        mTrackingView.debug();
                    }
                });
        }
    }

    void onBarViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Tracking View --------------
        pixelFormat = PixelFormat.RGBX_8888;
        bg = mTrackingView.getBackground();
        if (bg != null) {
            pixelFormat = bg.getOpacity();
        }

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                pixelFormat);
//        lp.token = mStatusBarView.getWindowToken();
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("TrackingView");
        lp.y = mTrackingPosition;
        mTrackingParams = lp;

        WindowManagerImpl.getDefault().addView(mTrackingView, lp);
    }

    void onTrackingViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Expanded View --------------
        pixelFormat = PixelFormat.TRANSLUCENT;

        final int disph = mDisplay.getHeight();
        lp = mExpandedDialog.getWindow().getAttributes();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = getExpandedHeight();
        lp.x = 0;
        mTrackingPosition = lp.y = -disph; // sufficiently large negative
        lp.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_DITHER
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.format = pixelFormat;
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBarExpanded");
        mExpandedDialog.getWindow().setAttributes(lp);
        mExpandedDialog.getWindow().setFormat(pixelFormat);
        mExpandedParams = lp;

        mExpandedDialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        mExpandedDialog.setContentView(mExpandedView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                           ViewGroup.LayoutParams.MATCH_PARENT));
        mExpandedDialog.getWindow().setBackgroundDrawable(null);
        mExpandedDialog.show();
        FrameLayout hack = (FrameLayout)mExpandedView.getParent();
    }

    void setDateViewVisibility(boolean visible, int anim) {
        mDateView.setUpdates(visible);
        mDateView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mDateView.startAnimation(loadAnim(anim, null));
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    void updateExpandedViewPos(int expandedPosition) {
        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos before expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition);
        }

        int h = mStatusBarView.getHeight();
        int disph = mDisplay.getHeight();

        // If the expanded view is not visible, make sure they're still off screen.
        // Maybe the view was resized.
        if (!mExpandedVisible) {
            if (mTrackingView != null) {
                mTrackingPosition = -disph;
                if (mTrackingParams != null) {
                    mTrackingParams.y = mTrackingPosition;
                    WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);
                }
            }
            if (mExpandedParams != null) {
                mExpandedParams.y = -disph;
                mExpandedDialog.getWindow().setAttributes(mExpandedParams);
            }
            return;
        }

        // tracking view...
        int pos;
        if (expandedPosition == EXPANDED_FULL_OPEN) {
            pos = h;
        }
        else if (expandedPosition == EXPANDED_LEAVE_ALONE) {
            pos = mTrackingPosition;
        }
        else {
            if (expandedPosition <= disph) {
                pos = expandedPosition;
            } else {
                pos = disph;
            }
            pos -= disph-h;
        }
        mTrackingPosition = mTrackingParams.y = pos;
        mTrackingParams.height = disph-h;
        WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);

        if (mExpandedParams != null) {
            mCloseView.getLocationInWindow(mPositionTmp);
            final int closePos = mPositionTmp[1];

            mExpandedContents.getLocationInWindow(mPositionTmp);
            final int contentsBottom = mPositionTmp[1] + mExpandedContents.getHeight();

            mExpandedParams.y = pos + mTrackingView.getHeight()
                    - (mTrackingParams.height-closePos) - contentsBottom;
            int max = h;
            if (mExpandedParams.y > max) {
                mExpandedParams.y = max;
            }
            int min = mTrackingPosition;
            if (mExpandedParams.y < min) {
                mExpandedParams.y = min;
            }

            boolean visible = (mTrackingPosition + mTrackingView.getHeight()) > h;
            if (!visible) {
                // if the contents aren't visible, move the expanded view way off screen
                // because the window itself extends below the content view.
                mExpandedParams.y = -disph;
            }
            visibilityChanged(visible);
            mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        }

        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos after  expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition
                    + " mExpandedParams.y=" + mExpandedParams.y
                    + " mExpandedParams.height=" + mExpandedParams.height);
        }
    }

    int getExpandedHeight() {
        return mDisplay.getHeight() - mStatusBarView.getHeight() - mCloseView.getHeight();
    }

    void updateExpandedHeight() {
        if (mExpandedView != null) {
            mExpandedParams.height = getExpandedHeight();
            mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        }
    }

    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    private boolean mPanelSlightlyVisible;
    void visibilityChanged(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            try {
                mBarService.visibilityChanged(visible);
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
            }
        }
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
        public void onClick(View v) {
            mNotificationCallbacks.onClearAll();
            animateCollapse();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                //collapse();
            }
            else if (Telephony.Intents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN));
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
            }
        }
    };

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Slog.d(TAG, "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        boolean something = false;
        if (showPlmn) {
            mPlmnLabel.setVisibility(View.VISIBLE);
            if (plmn != null) {
                mPlmnLabel.setText(plmn);
            } else {
                mPlmnLabel.setText(com.android.internal.R.string.lockscreen_carrier_default);
            }
        } else {
            mPlmnLabel.setText("");
            mPlmnLabel.setVisibility(View.GONE);
        }
        if (showSpn && spn != null) {
            mSpnLabel.setText(spn);
            mSpnLabel.setVisibility(View.VISIBLE);
            something = true;
        } else {
            mSpnLabel.setText("");
            mSpnLabel.setVisibility(View.GONE);
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
        Resources res = getResources();

        mClearButton.setText(getText(R.string.status_bar_clear_all_button));
        mOngoingTitle.setText(getText(R.string.status_bar_ongoing_events_title));
        mLatestTitle.setText(getText(R.string.status_bar_latest_events_title));
        mNoNotificationsTitle.setText(getText(R.string.status_bar_no_notifications_title));

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        if (false) Slog.v(TAG, "updateResources");
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
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
}
