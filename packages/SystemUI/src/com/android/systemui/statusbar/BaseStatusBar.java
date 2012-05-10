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

package com.android.systemui.statusbar;

import java.util.ArrayList;

import android.app.ActivityManagerNative;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.PopupMenu;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;
import com.android.internal.widget.SizeAdaptiveLayout;
import com.android.systemui.SearchPanelView;
import com.android.systemui.SystemUI;
import com.android.systemui.recent.RecentsPanelView;
import com.android.systemui.recent.RecentTasksLoader;
import com.android.systemui.recent.TaskDescription;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.tablet.StatusBarPanel;

import com.android.systemui.R;

public abstract class BaseStatusBar extends SystemUI implements
    CommandQueue.Callbacks, RecentsPanelView.OnRecentsPanelVisibilityChangedListener {
    static final String TAG = "StatusBar";
    private static final boolean DEBUG = false;

    protected static final int MSG_OPEN_RECENTS_PANEL = 1020;
    protected static final int MSG_CLOSE_RECENTS_PANEL = 1021;
    protected static final int MSG_PRELOAD_RECENT_APPS = 1022;
    protected static final int MSG_CANCEL_PRELOAD_RECENT_APPS = 1023;
    protected static final int MSG_OPEN_SEARCH_PANEL = 1024;
    protected static final int MSG_CLOSE_SEARCH_PANEL = 1025;

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;
    protected H mHandler = createHandler();

    // used to notify status bar for suppressing notification LED
    protected boolean mPanelSlightlyVisible;

    // Search panel
    protected SearchPanelView mSearchPanelView;

    // Recent apps
    protected RecentsPanelView mRecentsPanel;
    protected RecentTasksLoader mRecentTasksLoader;

    protected PopupMenu mNotificationBlamePopup;

    // UI-specific methods

    /**
     * Create all windows necessary for the status bar (including navigation, overlay panels, etc)
     * and add them to the window manager.
     */
    protected abstract void createAndAddWindows();

    protected Display mDisplay;
    private IWindowManager mWindowManager;


    public IWindowManager getWindowManager() {
        return mWindowManager;
    }

    public Display getDisplay() {
        return mDisplay;
    }

    public IStatusBarService getStatusBarService() {
        return mBarService;
    }

    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();

        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        // Connect in to the status bar manager service
        StatusBarIconList iconList = new StatusBarIconList();
        ArrayList<IBinder> notificationKeys = new ArrayList<IBinder>();
        ArrayList<StatusBarNotification> notifications = new ArrayList<StatusBarNotification>();
        mCommandQueue = new CommandQueue(this, iconList);

        int[] switches = new int[7];
        ArrayList<IBinder> binders = new ArrayList<IBinder>();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconList, notificationKeys, notifications,
                    switches, binders);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        createAndAddWindows();

        disable(switches[0]);
        setSystemUiVisibility(switches[1], 0xffffffff);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4]);
        setHardKeyboardStatus(switches[5] != 0, switches[6] != 0);

        // Set up the initial icon state
        int N = iconList.size();
        int viewIndex = 0;
        for (int i=0; i<N; i++) {
            StatusBarIcon icon = iconList.getIcon(i);
            if (icon != null) {
                addIcon(iconList.getSlot(i), i, viewIndex, icon);
                viewIndex++;
            }
        }

        // Set up the initial notification state
        N = notificationKeys.size();
        if (N == notifications.size()) {
            for (int i=0; i<N; i++) {
                addNotification(notificationKeys.get(i), notifications.get(i));
            }
        } else {
            Log.wtf(TAG, "Notification list length mismatch: keys=" + N
                    + " notifications=" + notifications.size());
        }

        if (DEBUG) {
            Slog.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x menu=0x%08x imeButton=0x%08x",
                   iconList.size(),
                   switches[0],
                   switches[1],
                   switches[2],
                   switches[3]
                   ));
        }
    }

    protected View updateNotificationVetoButton(View row, StatusBarNotification n) {
        View vetoButton = row.findViewById(R.id.veto);
        if (n.isClearable()) {
            final String _pkg = n.pkg;
            final String _tag = n.tag;
            final int _id = n.id;
            vetoButton.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            mBarService.onNotificationClear(_pkg, _tag, _id);
                        } catch (RemoteException ex) {
                            // system process is dead if we're here.
                        }
                    }
                });
            vetoButton.setVisibility(View.VISIBLE);
        } else {
            vetoButton.setVisibility(View.GONE);
        }
        return vetoButton;
    }


    protected void applyLegacyRowBackground(StatusBarNotification sbn, View content) {
        if (sbn.notification.contentView.getLayoutId() !=
                com.android.internal.R.layout.notification_template_base) {
            int version = 0;
            try {
                ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(sbn.pkg, 0);
                version = info.targetSdkVersion;
            } catch (NameNotFoundException ex) {
                Slog.e(TAG, "Failed looking up ApplicationInfo for " + sbn.pkg, ex);
            }
            if (version > 0 && version < Build.VERSION_CODES.GINGERBREAD) {
                content.setBackgroundResource(R.drawable.notification_row_legacy_bg);
            } else {
                content.setBackgroundResource(com.android.internal.R.drawable.notification_bg);
            }
        }
    }

    private void startApplicationDetailsActivity(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    protected View.OnLongClickListener getNotificationLongClicker() { 
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                final String packageNameF = (String) v.getTag();
                if (packageNameF == null) return false;
                if (v.getWindowToken() == null) return false;
                mNotificationBlamePopup = new PopupMenu(mContext, v);
                mNotificationBlamePopup.getMenuInflater().inflate(
                        R.menu.notification_popup_menu,
                        mNotificationBlamePopup.getMenu());
                mNotificationBlamePopup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == R.id.notification_inspect_item) {
                            startApplicationDetailsActivity(packageNameF);
                            animateCollapse();
                        } else {
                            return false;
                        }
                        return true;
                    }
                });
                mNotificationBlamePopup.show();

                return true;
            }
        };
    }

    public void dismissPopups() {
        if (mNotificationBlamePopup != null) {
            mNotificationBlamePopup.dismiss();
            mNotificationBlamePopup = null;
        }
    }

    public void dismissIntruder() {
        // pass
    }

    @Override
    public void toggleRecentApps() {
        int msg = (mRecentsPanel.getVisibility() == View.VISIBLE)
            ? MSG_CLOSE_RECENTS_PANEL : MSG_OPEN_RECENTS_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
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
    public void showSearchPanel() {
        int msg = MSG_OPEN_SEARCH_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void hideSearchPanel() {
        int msg = MSG_CLOSE_SEARCH_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void onRecentsPanelVisibilityChanged(boolean visible) {
    }

    protected abstract WindowManager.LayoutParams getRecentsLayoutParams(
            LayoutParams layoutParams);

    protected abstract WindowManager.LayoutParams getSearchLayoutParams(
            LayoutParams layoutParams);

    protected void updateRecentsPanel(int recentsResId) {
        // Recents Panel
        boolean visible = false;
        ArrayList<TaskDescription> recentTasksList = null;
        boolean firstScreenful = false;
        if (mRecentsPanel != null) {
            visible = mRecentsPanel.isShowing();
            WindowManagerImpl.getDefault().removeView(mRecentsPanel);
            if (visible) {
                recentTasksList = mRecentsPanel.getRecentTasksList();
                firstScreenful = mRecentsPanel.getFirstScreenful();
            }
        }

        // Provide RecentsPanelView with a temporary parent to allow layout params to work.
        LinearLayout tmpRoot = new LinearLayout(mContext);
        mRecentsPanel = (RecentsPanelView) LayoutInflater.from(mContext).inflate(
                recentsResId, tmpRoot, false);
        mRecentsPanel.setRecentTasksLoader(mRecentTasksLoader);
        mRecentTasksLoader.setRecentsPanel(mRecentsPanel);
        mRecentsPanel.setOnTouchListener(
                 new TouchOutsideListener(MSG_CLOSE_RECENTS_PANEL, mRecentsPanel));
        mRecentsPanel.setVisibility(View.GONE);


        WindowManager.LayoutParams lp = getRecentsLayoutParams(mRecentsPanel.getLayoutParams());

        WindowManagerImpl.getDefault().addView(mRecentsPanel, lp);
        mRecentsPanel.setBar(this);
        if (visible) {
            mRecentsPanel.show(true, false, recentTasksList, firstScreenful);
        }

    }

    protected void updateSearchPanel() {
        // Search Panel
        boolean visible = false;
        if (mSearchPanelView != null) {
            visible = mSearchPanelView.isShowing();
            WindowManagerImpl.getDefault().removeView(mSearchPanelView);
        }

        // Provide SearchPanel with a temporary parent to allow layout params to work.
        LinearLayout tmpRoot = new LinearLayout(mContext);
        mSearchPanelView = (SearchPanelView) LayoutInflater.from(mContext).inflate(
                 R.layout.status_bar_search_panel, tmpRoot, false);
        mSearchPanelView.setOnTouchListener(
                 new TouchOutsideListener(MSG_CLOSE_SEARCH_PANEL, mSearchPanelView));
        mSearchPanelView.setVisibility(View.GONE);

        WindowManager.LayoutParams lp = getSearchLayoutParams(mSearchPanelView.getLayoutParams());

        WindowManagerImpl.getDefault().addView(mSearchPanelView, lp);
        mSearchPanelView.setBar(this);
        if (visible) {
            mSearchPanelView.show(true, false);
        }
    }

    protected H createHandler() {
         return new H();
    }

    protected class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
             case MSG_OPEN_RECENTS_PANEL:
                  if (DEBUG) Slog.d(TAG, "opening recents panel");
                  if (mRecentsPanel != null) {
                      mRecentsPanel.show(true, true);
                  }
                  break;
             case MSG_CLOSE_RECENTS_PANEL:
                  if (DEBUG) Slog.d(TAG, "closing recents panel");
                  if (mRecentsPanel != null && mRecentsPanel.isShowing()) {
                      mRecentsPanel.show(false, true);
                  }
                  break;
             case MSG_PRELOAD_RECENT_APPS:
                  if (DEBUG) Slog.d(TAG, "preloading recents");
                  mRecentsPanel.preloadRecentTasksList();
                  break;
             case MSG_CANCEL_PRELOAD_RECENT_APPS:
                  if (DEBUG) Slog.d(TAG, "cancel preloading recents");
                  mRecentsPanel.clearRecentTasksList();
                  break;
             case MSG_OPEN_SEARCH_PANEL:
                 if (DEBUG) Slog.d(TAG, "opening search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isAssistantAvailable()) {
                     mSearchPanelView.show(true, true);
                 }
                 break;
             case MSG_CLOSE_SEARCH_PANEL:
                 if (DEBUG) Slog.d(TAG, "closing search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isShowing()) {
                     mSearchPanelView.show(false, true);
                 }
                 break;
            }
        }
    }

    public class TouchOutsideListener implements View.OnTouchListener {
        private int mMsg;
        private StatusBarPanel mPanel;

        public TouchOutsideListener(int msg, StatusBarPanel panel) {
            mMsg = msg;
            mPanel = panel;
        }

        public boolean onTouch(View v, MotionEvent ev) {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_OUTSIDE
                || (action == MotionEvent.ACTION_DOWN
                    && !mPanel.isInContentArea((int)ev.getX(), (int)ev.getY()))) {
                mHandler.removeMessages(mMsg);
                mHandler.sendEmptyMessage(mMsg);
                return true;
            }
            return false;
        }
    }

    protected void workAroundBadLayerDrawableOpacity(View v) {
    }

    protected  boolean inflateViews(NotificationData.Entry entry, ViewGroup parent) {
        int rowHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_height);
        int minHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        StatusBarNotification sbn = entry.notification;
        RemoteViews oneU = sbn.notification.contentView;
        RemoteViews large = sbn.notification.bigContentView;
        if (oneU == null) {
            return false;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(R.layout.status_bar_notification_row, parent, false);

        // for blaming (see SwipeHelper.setLongPressListener)
        row.setTag(sbn.pkg);

        // XXX: temporary: while testing big notifications, auto-expand all of them
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        Boolean expandable = Boolean.FALSE;
        if (large != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            expandable = Boolean.TRUE;
        } else {
            lp.height = rowHeight;
        }
        row.setLayoutParams(lp);
        row.setTag(R.id.expandable_tag, expandable);
        workAroundBadLayerDrawableOpacity(row);
        View vetoButton = updateNotificationVetoButton(row, sbn);
        vetoButton.setContentDescription(mContext.getString(
                R.string.accessibility_remove_notification));

        // NB: the large icon is now handled entirely by the template

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(R.id.content);
        ViewGroup adaptive = (ViewGroup)row.findViewById(R.id.adaptive);

        // Ensure that R.id.content is properly set to 64dp high if 1U
        lp = content.getLayoutParams();
        if (large == null) {
            lp.height = minHeight;
        }
        content.setLayoutParams(lp);

        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        PendingIntent contentIntent = sbn.notification.contentIntent;
        if (contentIntent != null) {
            final View.OnClickListener listener = new NotificationClicker(contentIntent,
                    sbn.pkg, sbn.tag, sbn.id);
            content.setOnClickListener(listener);
        } else {
            content.setOnClickListener(null);
        }

        View expandedOneU = null;
        View expandedLarge = null;
        Exception exception = null;
        try {
            expandedOneU = oneU.apply(mContext, adaptive);
            if (large != null) {
                expandedLarge = large.apply(mContext, adaptive);
            }
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (expandedOneU == null && expandedLarge == null) {
            final String ident = sbn.pkg + "/0x" + Integer.toHexString(sbn.id);
            Slog.e(TAG, "couldn't inflate view for notification " + ident, exception);
            return false;
        } else {
            if (expandedOneU != null) {
                SizeAdaptiveLayout.LayoutParams params =
                        new SizeAdaptiveLayout.LayoutParams(expandedOneU.getLayoutParams());
                params.minHeight = minHeight;
                params.maxHeight = minHeight;
                adaptive.addView(expandedOneU, params);
            }
            if (expandedLarge != null) {
                SizeAdaptiveLayout.LayoutParams params =
                        new SizeAdaptiveLayout.LayoutParams(expandedLarge.getLayoutParams());
                params.minHeight = minHeight+1;
                params.maxHeight = SizeAdaptiveLayout.LayoutParams.UNBOUNDED;
                adaptive.addView(expandedLarge, params);
            }
            row.setDrawingCacheEnabled(true);
        }

        applyLegacyRowBackground(sbn, content);

        entry.row = row;
        entry.content = content;
        entry.expanded = expandedOneU;
        entry.expandedLarge = expandedOneU;

        return true;
    }

    public NotificationClicker makeClicker(PendingIntent intent, String pkg, String tag, int id) {
        return new NotificationClicker(intent, pkg, tag, id);
    }

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
            visibilityChanged(false);

            // If this click was on the intruder alert, hide that instead
//            mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
        }
    }
    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    protected void visibilityChanged(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            try {
                mBarService.onPanelRevealed();
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
            }
        }
    }

}
