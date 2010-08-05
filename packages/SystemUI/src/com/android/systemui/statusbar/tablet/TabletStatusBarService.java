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

package com.android.systemui.statusbar.tablet;

import android.animation.Animator;
import android.app.ActivityManagerNative;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

import com.android.systemui.statusbar.*;
import com.android.systemui.R;

public class TabletStatusBarService extends StatusBarService {
    public static final boolean DEBUG = false;
    public static final String TAG = "TabletStatusBar";



    int mIconSize;

    H mHandler = new H();

    // tracking all current notifications
    private NotificationData mNotns = new NotificationData();
    
    View mStatusBarView;
    NotificationIconArea mNotificationIconArea;

    View mNotificationPanel;
    View mSystemPanel;

    ViewGroup mPile;
    TextView mClearButton;

    NotificationIconArea.IconLayout mIconLayout;

    KickerController mKicker;
    View mKickerView;
    boolean mTicking;
    boolean mExpandedVisible;

    // for disabling the status bar
    int mDisabled = 0;

    protected void addPanelWindows() {
        mNotificationPanel = View.inflate(this, R.layout.sysbar_panel_notifications, null);
        mSystemPanel = View.inflate(this, R.layout.sysbar_panel_system, null);

        mNotificationPanel.setVisibility(View.GONE);
        mSystemPanel.setVisibility(View.GONE);

        final Resources res = getResources();
        final int barHeight= res.getDimensionPixelSize(
            com.android.internal.R.dimen.status_bar_height);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                400, // ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.setTitle("NotificationPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_SlidingCard;

        WindowManagerImpl.getDefault().addView(mNotificationPanel, lp);

        lp = new WindowManager.LayoutParams(
                400, // ViewGroup.LayoutParams.WRAP_CONTENT,
                200, // ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.setTitle("SystemPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_SlidingCard;

        WindowManagerImpl.getDefault().addView(mSystemPanel, lp);
    }

    @Override
    public void onCreate() {
        super.onCreate(); // will add the main bar view
    }

    protected View makeStatusBarView() {
        Resources res = getResources();

        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

        final View sb = View.inflate(this, R.layout.status_bar, null);
        mStatusBarView = sb;

        // the more notifications icon
        mNotificationIconArea = (NotificationIconArea)sb.findViewById(R.id.notificationIcons);

        // where the icons go
        mIconLayout = (NotificationIconArea.IconLayout) sb.findViewById(R.id.icons);

        mKicker = new KickerController((Context)this, mStatusBarView);

        // Add the windows
        addPanelWindows();

        // Lorem ipsum, Dolores
        TextView tv = ((TextView) mSystemPanel.findViewById(R.id.systemPanelDummy));
        if (tv != null) tv.setText("System status: great");

        mPile = (ViewGroup)mNotificationPanel.findViewById(R.id.content);
        mPile.removeAllViews();
        
        ScrollView scroller = (ScrollView)mPile.getParent();
        scroller.setFillViewport(true);

        mClearButton = (TextView)mNotificationPanel.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);

        return sb;
    }

    protected int getStatusBarGravity() {
        return Gravity.BOTTOM | Gravity.FILL_HORIZONTAL;
    }

    private class H extends Handler {
        public static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
        public static final int MSG_CLOSE_NOTIFICATION_PANEL = 1001;
        public static final int MSG_OPEN_SYSTEM_PANEL = 1010;
        public static final int MSG_CLOSE_SYSTEM_PANEL = 1011;
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_OPEN_NOTIFICATION_PANEL:
                    if (DEBUG) Slog.d(TAG, "opening notifications panel");
                    mNotificationPanel.setVisibility(View.VISIBLE);
                    mExpandedVisible = true;
                    break;
                case MSG_CLOSE_NOTIFICATION_PANEL:
                    if (DEBUG) Slog.d(TAG, "closing notifications panel");
                    mNotificationPanel.setVisibility(View.GONE);
                    mExpandedVisible = false;
                    break;
                case MSG_OPEN_SYSTEM_PANEL:
                    if (DEBUG) Slog.d(TAG, "opening system panel");
                    mSystemPanel.setVisibility(View.VISIBLE);
                    break;
                case MSG_CLOSE_SYSTEM_PANEL:
                    if (DEBUG) Slog.d(TAG, "closing system panel");
                    mSystemPanel.setVisibility(View.GONE);
                    break;
            }
        }
    }

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        // TODO
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        // TODO
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        // TODO
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        if (DEBUG) Slog.d(TAG, "addNotification(" + key + " -> " + notification + ")");
        addNotificationViews(key, notification);
        // tick()
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        if (DEBUG) Slog.d(TAG, "updateNotification(" + key + " -> " + notification + ") // TODO");
        
        final NotificationData.Entry oldEntry = mNotns.findByKey(key);
        if (oldEntry == null) {
            Slog.w(TAG, "updateNotification for unknown key: " + key);
            return;
        }

        final StatusBarNotification oldNotification = oldEntry.notification;
        final RemoteViews oldContentView = oldNotification.notification.contentView;

        final RemoteViews contentView = notification.notification.contentView;

        if (false) {
            Slog.d(TAG, "old notification: when=" + oldNotification.notification.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " expanded=" + oldEntry.expanded
                    + " contentView=" + oldContentView);
            Slog.d(TAG, "new notification: when=" + notification.notification.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " contentView=" + contentView);
        }

        // Can we just reapply the RemoteViews in place?  If when didn't change, the order
        // didn't change.
        if (notification.notification.when == oldNotification.notification.when
                && notification.isOngoing() == oldNotification.isOngoing()
                && oldEntry.expanded != null
                && contentView != null
                && oldContentView != null
                && contentView.getPackage() != null
                && oldContentView.getPackage() != null
                && oldContentView.getPackage().equals(contentView.getPackage())
                && oldContentView.getLayoutId() == contentView.getLayoutId()) {
            if (DEBUG) Slog.d(TAG, "reusing notification for key: " + key);
            oldEntry.notification = notification;
            try {
                // Reapply the RemoteViews
                contentView.reapply(this, oldEntry.content);
                // update the contentIntent
                final PendingIntent contentIntent = notification.notification.contentIntent;
                if (contentIntent != null) {
                    oldEntry.content.setOnClickListener(new NotificationClicker(contentIntent,
                                notification.pkg, notification.tag, notification.id));
                }
                // Update the icon.
                final StatusBarIcon ic = new StatusBarIcon(notification.pkg,
                        notification.notification.icon, notification.notification.iconLevel,
                        notification.notification.number);
                if (!oldEntry.icon.set(ic)) {
                    handleNotificationError(key, notification, "Couldn't update icon: " + ic);
                    return;
                }
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Slog.w(TAG, "Couldn't reapply views for package " + contentView.getPackage(), e);
                removeNotificationViews(key);
                addNotificationViews(key, notification);
            }
        } else {
            if (DEBUG) Slog.d(TAG, "not reusing notification for key: " + key);
            removeNotificationViews(key);
            addNotificationViews(key, notification);
        }
        // TODO: kicker; immersive mode
    }

    public void removeNotification(IBinder key) {
        if (DEBUG) Slog.d(TAG, "removeNotification(" + key + ") // TODO");
        removeNotificationViews(key);
    }

    public void disable(int state) {
        /*
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
                    mKicker.halt();
                } else {
                    mNotificationIconArea.setVisibility(View.INVISIBLE);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    mNotificationIconArea.setVisibility(View.VISIBLE);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_TICKER: yes");
                mKicker.halt();
            }
        }
        */
    }

    void performDisableActions(int net) {
        /*
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
                    mNotificationIconArea.setVisibility(View.INVISIBLE);
                    mKicker.halt();
                } else {
                    mNotificationIconArea.setVisibility(View.INVISIBLE);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    mNotificationIconArea.setVisibility(View.VISIBLE);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (net & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                mKicker.halt();
            }
        }
        */
    }

    private void tick(StatusBarNotification n) {
        // Show the ticker if one is requested. Also don't do this
        // until status bar window is attached to the window manager,
        // because...  well, what's the point otherwise?  And trying to
        // run a ticker without being attached will crash!
        if (n.notification.tickerText != null && mStatusBarView.getWindowToken() != null) {
            if (0 == (mDisabled & (StatusBarManager.DISABLE_NOTIFICATION_ICONS
                            | StatusBarManager.DISABLE_NOTIFICATION_TICKER))) {
                mKicker.addEntry(n);
            }
        }
    }

    private class KickerController {
        View mView;
        ImageView mKickerIcon;
        TextSwitcher mKickerText;

        public KickerController(Context context, View sb) {
            mView = sb.findViewById(R.id.ticker);
            mKickerIcon = (ImageView) mView.findViewById(R.id.tickerIcon);
            mKickerText = (TextSwitcher) mView.findViewById(R.id.tickerText);
        }

        public void halt() {
            tickerHalting();
        }

        public void addEntry(StatusBarNotification n) {
            mKickerIcon.setImageResource(n.notification.icon);
            mKickerText.setCurrentText(n.notification.tickerText);
            tickerStarting();
        }

        public void tickerStarting() {
            mTicking = true;
            mIconLayout.setVisibility(View.GONE);
            mKickerView.setVisibility(View.VISIBLE);
        }

        public void tickerDone() {
            mIconLayout.setVisibility(View.VISIBLE);
            mKickerView.setVisibility(View.GONE);
            mTicking = false;
        }

        public void tickerHalting() {
            mIconLayout.setVisibility(View.VISIBLE);
            mKickerView.setVisibility(View.GONE);
            mTicking = false;
        }
    }

    public void animateExpand() {
        mHandler.removeMessages(H.MSG_OPEN_NOTIFICATION_PANEL);
        mHandler.sendEmptyMessage(H.MSG_OPEN_NOTIFICATION_PANEL);
    }

    public void animateCollapse() {
        mHandler.removeMessages(H.MSG_CLOSE_NOTIFICATION_PANEL);
        mHandler.sendEmptyMessage(H.MSG_CLOSE_NOTIFICATION_PANEL);
        mHandler.removeMessages(H.MSG_CLOSE_SYSTEM_PANEL);
        mHandler.sendEmptyMessage(H.MSG_CLOSE_SYSTEM_PANEL);
    }

    public void notificationIconsClicked(View v) {
        if (DEBUG) Slog.d(TAG, "clicked notification icons");
        mHandler.removeMessages(H.MSG_CLOSE_SYSTEM_PANEL);
        mHandler.sendEmptyMessage(H.MSG_CLOSE_SYSTEM_PANEL);

        int msg = (mNotificationPanel.getVisibility() == View.GONE) 
            ? H.MSG_OPEN_NOTIFICATION_PANEL
            : H.MSG_CLOSE_NOTIFICATION_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    public void systemInfoClicked(View v) {
        if (DEBUG) Slog.d(TAG, "clicked system info");
        mHandler.removeMessages(H.MSG_CLOSE_NOTIFICATION_PANEL);
        mHandler.sendEmptyMessage(H.MSG_CLOSE_NOTIFICATION_PANEL);

        int msg = (mSystemPanel.getVisibility() == View.GONE) 
            ? H.MSG_OPEN_SYSTEM_PANEL
            : H.MSG_CLOSE_SYSTEM_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    /**
     * Cancel this notification and tell the status bar service about the failure. Hold no locks.
     */
    void handleNotificationError(IBinder key, StatusBarNotification n, String message) {
        removeNotification(key);
        try {
            mBarService.onNotificationError(n.pkg, n.tag, n.id, n.uid, n.initialPid, message);
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                mBarService.onClearAllNotifications();
            } catch (RemoteException ex) {
                // system process is dead if we're here.
            }
            animateCollapse();
        }
    };

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
            } catch (RemoteException e) {
            }

            if (mIntent != null) {
                int[] pos = new int[2];
                v.getLocationOnScreen(pos);
                Intent overlay = new Intent();
                overlay.setSourceBounds(
                        new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
                try {
                    mIntent.send(TabletStatusBarService.this, 0, overlay);
                } catch (PendingIntent.CanceledException e) {
                    // the stack trace isn't very helpful here.  Just log the exception message.
                    Slog.w(TAG, "Sending contentIntent failed: " + e);
                }
            }

            try {
                mBarService.onNotificationClick(mPkg, mTag, mId);
            } catch (RemoteException ex) {
                // system process is dead if we're here.
            }

            // close the shade if it was open
            animateCollapse();

            // If this click was on the intruder alert, hide that instead
//            mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
        }
    }

    StatusBarNotification removeNotificationViews(IBinder key) {
        NotificationData.Entry entry = mNotns.remove(key);
        if (entry == null) {
            Slog.w(TAG, "removeNotification for unknown key: " + key);
            return null;
        }
        // Remove the expanded view.
        ViewGroup rowParent = (ViewGroup)entry.row.getParent();
        if (rowParent != null) rowParent.removeView(entry.row);
        // Remove the icon.
//        ViewGroup iconParent = (ViewGroup)entry.icon.getParent();
//        if (iconParent != null) iconParent.removeView(entry.icon);
        refreshIcons();

        return entry.notification;
    }

    StatusBarIconView addNotificationViews(IBinder key, StatusBarNotification notification) {
        if (DEBUG) {
            Slog.d(TAG, "addNotificationViews(key=" + key + ", notification=" + notification);
        }
        // Construct the icon.
        final StatusBarIconView iconView = new StatusBarIconView(this,
                notification.pkg + "/0x" + Integer.toHexString(notification.id));
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        final StatusBarIcon ic = new StatusBarIcon(notification.pkg,
                    notification.notification.icon,
                    notification.notification.iconLevel,
                    notification.notification.number);
        if (!iconView.set(ic)) {
            handleNotificationError(key, notification, "Couldn't attach StatusBarIcon: " + ic);
            return null;
        }
        // Construct the expanded view.
        NotificationData.Entry entry = new NotificationData.Entry(key, notification, iconView);
        if (!inflateViews(entry, mPile)) {
            handleNotificationError(key, notification, "Couldn't expand RemoteViews for: "
                    + notification);
            return null;
        }
        // Add the icon.
        mNotns.add(entry);
        refreshIcons();

        return iconView;
    }

    private void refreshIcons() {
        // XXX: need to implement a new limited linear layout class
        // to avoid removing & readding everything

        int N = mNotns.size();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(mIconSize, mIconSize);

        if (DEBUG) {
            Slog.d(TAG, "refreshing icons (" + N + " notifications, mIconLayout="
                    + mIconLayout + ", mPile=" + mPile);
        }

        mIconLayout.removeAllViews();
        for (int i=0; i<4; i++) {
            if (i>=N) break;
            mIconLayout.addView(mNotns.get(N-i-1).icon, i, params);
        }

        mPile.removeAllViews();
        for (int i=0; i<N; i++) {
            mPile.addView(mNotns.get(N-i-1).row);
        }
    }

    private boolean inflateViews(NotificationData.Entry entry, ViewGroup parent) {
        StatusBarNotification sbn = entry.notification;
        RemoteViews remoteViews = sbn.notification.contentView;
        if (remoteViews == null) {
            return false;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(R.layout.status_bar_latest_event, parent, false);
        View vetoButton = row.findViewById(R.id.veto);
        final String _pkg = sbn.pkg;
        final String _tag = sbn.tag;
        final int _id = sbn.id;
        vetoButton.setOnClickListener(new View.OnClickListener() { 
                public void onClick(View v) {
                    try {
                        mBarService.onNotificationClear(_pkg, _tag, _id);
                    } catch (RemoteException ex) {
                        // system process is dead if we're here.
                    }
//                    animateCollapse();
                }
            });

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(R.id.content);
        // XXX: update to allow controls within notification views
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
//        content.setOnFocusChangeListener(mFocusChangeListener);
        PendingIntent contentIntent = sbn.notification.contentIntent;
        if (contentIntent != null) {
            content.setOnClickListener(new NotificationClicker(contentIntent,
                        sbn.pkg, sbn.tag, sbn.id));
        }

        View expanded = null;
        Exception exception = null;
        try {
            expanded = remoteViews.apply(this, content);
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (expanded == null) {
            String ident = sbn.pkg + "/0x" + Integer.toHexString(sbn.id);
            Slog.e(TAG, "couldn't inflate view for notification " + ident, exception);
            return false;
        } else {
            content.addView(expanded);
            row.setDrawingCacheEnabled(true);
        }

        entry.row = row;
        entry.content = content;
        entry.expanded = expanded;

        return true;
    }
}
