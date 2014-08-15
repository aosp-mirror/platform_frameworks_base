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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.AnimationUtils;
import android.widget.DateTimeView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SearchPanelView;
import com.android.systemui.SwipeHelper;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.phone.KeyguardTouchDelegate;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.HeadsUpNotificationView;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import java.util.ArrayList;
import java.util.Locale;

import static com.android.keyguard.KeyguardHostView.OnDismissAction;

public abstract class BaseStatusBar extends SystemUI implements
        CommandQueue.Callbacks, ActivatableNotificationView.OnActivatedListener,
        RecentsComponent.Callbacks, ExpandableNotificationRow.ExpansionLogger,
        NotificationData.Environment {
    public static final String TAG = "StatusBar";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean MULTIUSER_DEBUG = false;

    protected static final int MSG_SHOW_RECENT_APPS = 1019;
    protected static final int MSG_HIDE_RECENT_APPS = 1020;
    protected static final int MSG_TOGGLE_RECENTS_APPS = 1021;
    protected static final int MSG_PRELOAD_RECENT_APPS = 1022;
    protected static final int MSG_CANCEL_PRELOAD_RECENT_APPS = 1023;
    protected static final int MSG_SHOW_NEXT_AFFILIATED_TASK = 1024;
    protected static final int MSG_SHOW_PREV_AFFILIATED_TASK = 1025;
    protected static final int MSG_OPEN_SEARCH_PANEL = 1026;
    protected static final int MSG_CLOSE_SEARCH_PANEL = 1027;
    protected static final int MSG_SHOW_HEADS_UP = 1028;
    protected static final int MSG_HIDE_HEADS_UP = 1029;
    protected static final int MSG_ESCALATE_HEADS_UP = 1030;
    protected static final int MSG_DECAY_HEADS_UP = 1031;

    protected static final boolean ENABLE_HEADS_UP = true;
    // scores above this threshold should be displayed in heads up mode.
    protected static final int INTERRUPTION_THRESHOLD = 10;
    protected static final String SETTING_HEADS_UP_TICKER = "ticker_gets_heads_up";

    // Should match the value in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";

    public static final int EXPANDED_LEAVE_ALONE = -10000;
    public static final int EXPANDED_FULL_OPEN = -10001;

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;
    protected H mHandler = createHandler();

    // all notifications
    protected NotificationData mNotificationData;
    protected NotificationStackScrollLayout mStackScroller;

    // for heads up notifications
    protected HeadsUpNotificationView mHeadsUpNotificationView;
    protected int mHeadsUpNotificationDecay;

    // used to notify status bar for suppressing notification LED
    protected boolean mPanelSlightlyVisible;

    // Search panel
    protected SearchPanelView mSearchPanelView;

    protected int mCurrentUserId = 0;
    final protected SparseArray<UserInfo> mCurrentProfiles = new SparseArray<UserInfo>();

    protected int mLayoutDirection = -1; // invalid
    private Locale mLocale;
    private float mFontScale;

    protected boolean mUseHeadsUp = false;
    protected boolean mHeadsUpTicker = false;
    protected boolean mDisableNotificationAlerts = false;

    protected DevicePolicyManager mDevicePolicyManager;
    protected IDreamManager mDreamManager;
    PowerManager mPowerManager;
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    protected int mRowMinHeight;
    protected int mRowMaxHeight;

    // public mode, private notifications, etc
    private boolean mLockscreenPublicMode = false;
    private final SparseBooleanArray mUsersAllowingPrivateNotifications = new SparseBooleanArray();
    private NotificationColorUtil mNotificationColorUtil = NotificationColorUtil.getInstance();

    private UserManager mUserManager;

    // UI-specific methods

    /**
     * Create all windows necessary for the status bar (including navigation, overlay panels, etc)
     * and add them to the window manager.
     */
    protected abstract void createAndAddWindows();

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;

    protected abstract void refreshLayout(int layoutDirection);

    protected Display mDisplay;

    private boolean mDeviceProvisioned = false;

    private RecentsComponent mRecents;

    protected int mZenMode;

    // which notification is currently being longpress-examined by the user
    private View mNotificationGutsExposed;

    private TimeInterpolator mLinearOutSlowIn, mFastOutLinearIn;

    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState;
    protected boolean mBouncerShowing;
    protected boolean mShowLockscreenNotifications;

    protected NotificationOverflowContainer mKeyguardIconOverflowContainer;
    protected DismissView mDismissView;
    protected EmptyShadeView mEmptyShadeView;

    @Override  // NotificationData.Environment
    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean provisioned = 0 != Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
            if (provisioned != mDeviceProvisioned) {
                mDeviceProvisioned = provisioned;
                updateNotifications();
            }
            final int mode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
            setZenMode(mode);

            updateLockscreenNotificationSetting();
        }
    };

    private final ContentObserver mLockscreenSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            // We don't know which user changed LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
            // so we just dump our cache ...
            mUsersAllowingPrivateNotifications.clear();
            // ... and refresh all the notifications
            updateNotifications();
        }
    };

    private RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {
        @Override
        public boolean onClickHandler(
                final View view, final PendingIntent pendingIntent, final Intent fillInIntent) {
            if (DEBUG) {
                Log.v(TAG, "Notification click handler invoked for intent: " + pendingIntent);
            }
            final boolean isActivity = pendingIntent.isActivity();
            if (isActivity) {
                final boolean keyguardShowing = mStatusBarKeyguardViewManager.isShowing();
                dismissKeyguardThenExecute(new OnDismissAction() {
                    @Override
                    public boolean onDismiss() {
                        if (keyguardShowing) {
                            try {
                                ActivityManagerNative.getDefault()
                                        .keyguardWaitingForActivityDrawn();
                                // The intent we are sending is for the application, which
                                // won't have permission to immediately start an activity after
                                // the user switches to home.  We know it is safe to do at this
                                // point, so make sure new activity switches are now allowed.
                                ActivityManagerNative.getDefault().resumeAppSwitches();
                            } catch (RemoteException e) {
                            }
                        }

                        boolean handled = superOnClickHandler(view, pendingIntent, fillInIntent);
                        overrideActivityPendingAppTransition(keyguardShowing);

                        // close the shade if it was open
                        if (handled) {
                            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
                            visibilityChanged(false);
                        }
                        // Wait for activity start.
                        return handled;
                    }
                });
                return true;
            } else {
                return super.onClickHandler(view, pendingIntent, fillInIntent);
            }
        }

        private boolean superOnClickHandler(View view, PendingIntent pendingIntent,
                Intent fillInIntent) {
            return super.onClickHandler(view, pendingIntent, fillInIntent);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                updateCurrentProfilesCache();
                if (true) Log.v(TAG, "userId " + mCurrentUserId + " is in the house");

                updateLockscreenNotificationSetting();

                userSwitched(mCurrentUserId);
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                updateCurrentProfilesCache();
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(
                    action)) {
                mUsersAllowingPrivateNotifications.clear();
                updateLockscreenNotificationSetting();
                updateNotifications();
            }
        }
    };

    private final NotificationListenerService mNotificationListener =
            new NotificationListenerService() {
        @Override
        public void onListenerConnected() {
            if (DEBUG) Log.d(TAG, "onListenerConnected");
            final StatusBarNotification[] notifications = getActiveNotifications();
            final RankingMap currentRanking = getCurrentRanking();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    for (StatusBarNotification sbn : notifications) {
                        addNotification(sbn, currentRanking);
                    }
                }
            });
        }

        @Override
        public void onNotificationPosted(final StatusBarNotification sbn,
                final RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onNotificationPosted: " + sbn);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Notification n = sbn.getNotification();
                    boolean isUpdate = mNotificationData.get(sbn.getKey()) != null
                            || isHeadsUp(sbn.getKey());
                    if (isUpdate) {
                        updateNotification(sbn, rankingMap);
                    } else {
                        addNotification(sbn, rankingMap);
                    }
                }
            });
        }

        @Override
        public void onNotificationRemoved(final StatusBarNotification sbn,
                final RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onNotificationRemoved: " + sbn);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    removeNotification(sbn.getKey(), rankingMap);
                }
            });
        }

        @Override
        public void onNotificationRankingUpdate(final RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onRankingUpdate");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateNotificationRanking(rankingMap);
                }
            });
        }

    };

    private void updateCurrentProfilesCache() {
        synchronized (mCurrentProfiles) {
            mCurrentProfiles.clear();
            if (mUserManager != null) {
                for (UserInfo user : mUserManager.getProfiles(mCurrentUserId)) {
                    mCurrentProfiles.put(user.id, user);
                }
            }
        }
    }

    public void start() {
        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDisplay = mWindowManager.getDefaultDisplay();
        mDevicePolicyManager = (DevicePolicyManager)mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mNotificationData = new NotificationData(this);

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mSettingsObserver.onChange(false); // set up
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE), false,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mRecents = getComponent(RecentsComponent.class);
        mRecents.setCallback(this);

        final Configuration currentConfig = mContext.getResources().getConfiguration();
        mLocale = currentConfig.locale;
        mLayoutDirection = TextUtils.getLayoutDirectionFromLocale(mLocale);
        mFontScale = currentConfig.fontScale;

        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        mLinearOutSlowIn = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.linear_out_slow_in);
        mFastOutLinearIn = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_linear_in);

        // Connect in to the status bar manager service
        StatusBarIconList iconList = new StatusBarIconList();
        mCommandQueue = new CommandQueue(this, iconList);

        int[] switches = new int[8];
        ArrayList<IBinder> binders = new ArrayList<IBinder>();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconList, switches, binders);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        createAndAddWindows();

        disable(switches[0], false /* animate */);
        setSystemUiVisibility(switches[1], 0xffffffff);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4], switches[7] != 0);
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

        // Set up the initial notification state.
        try {
            mNotificationListener.registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register notification listener", e);
        }


        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x menu=0x%08x imeButton=0x%08x",
                   iconList.size(),
                   switches[0],
                   switches[1],
                   switches[2],
                   switches[3]
                   ));
        }

        mCurrentUserId = ActivityManager.getCurrentUser();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        updateCurrentProfilesCache();
    }

    public void userSwitched(int newUserId) {
        // should be overridden
    }

    public boolean isHeadsUp(String key) {
      return mHeadsUpNotificationView != null && mHeadsUpNotificationView.isShowing(key);
    }

    @Override  // NotificationData.Environment
    public boolean isNotificationForCurrentProfiles(StatusBarNotification n) {
        final int thisUserId = mCurrentUserId;
        final int notificationUserId = n.getUserId();
        if (DEBUG && MULTIUSER_DEBUG) {
            Log.v(TAG, String.format("%s: current userid: %d, notification userid: %d",
                    n, thisUserId, notificationUserId));
        }
        synchronized (mCurrentProfiles) {
            return notificationUserId == UserHandle.USER_ALL
                    || mCurrentProfiles.get(notificationUserId) != null;
        }
    }

    /**
     * Takes the necessary steps to prepare the status bar for starting an activity, then starts it.
     * @param action A dismiss action that is called if it's safe to start the activity.
     */
    protected void dismissKeyguardThenExecute(OnDismissAction action) {
        action.onDismiss();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        final Locale locale = mContext.getResources().getConfiguration().locale;
        final int ld = TextUtils.getLayoutDirectionFromLocale(locale);
        final float fontScale = newConfig.fontScale;

        if (! locale.equals(mLocale) || ld != mLayoutDirection || fontScale != mFontScale) {
            if (DEBUG) {
                Log.v(TAG, String.format(
                        "config changed locale/LD: %s (%d) -> %s (%d)", mLocale, mLayoutDirection,
                        locale, ld));
            }
            mLocale = locale;
            mLayoutDirection = ld;
            refreshLayout(ld);
        }
    }

    protected View updateNotificationVetoButton(View row, StatusBarNotification n) {
        View vetoButton = row.findViewById(R.id.veto);
        if (n.isClearable() || (mHeadsUpNotificationView.getEntry() != null
                && mHeadsUpNotificationView.getEntry().row == row)) {
            final String _pkg = n.getPackageName();
            final String _tag = n.getTag();
            final int _id = n.getId();
            final int _userId = n.getUserId();
            vetoButton.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        // Accessibility feedback
                        v.announceForAccessibility(
                                mContext.getString(R.string.accessibility_notification_dismissed));
                        try {
                            mBarService.onNotificationClear(_pkg, _tag, _id, _userId);

                        } catch (RemoteException ex) {
                            // system process is dead if we're here.
                        }
                    }
                });
            vetoButton.setVisibility(View.VISIBLE);
        } else {
            vetoButton.setVisibility(View.GONE);
        }
        vetoButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return vetoButton;
    }


    protected void applyLegacyRowBackground(StatusBarNotification sbn,
            NotificationData.Entry entry) {
        int version = 0;
        try {
            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(sbn.getPackageName(), 0);
            version = info.targetSdkVersion;
        } catch (NameNotFoundException ex) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
        }

        if (entry.expanded.getId() != com.android.internal.R.id.status_bar_latest_event_content) {
            // Using custom RemoteViews
            if (version >= Build.VERSION_CODES.GINGERBREAD && version < Build.VERSION_CODES.L) {
                entry.row.setShowingLegacyBackground(true);
                entry.legacy = true;
            }
        } else {
            // Using platform templates
            final int color = sbn.getNotification().color;
            if (isMediaNotification(entry)) {
                entry.row.setTintColor(color);
            }
        }
    }

    public boolean isMediaNotification(NotificationData.Entry entry) {
        // TODO: confirm that there's a valid media key
        return entry.expandedBig != null &&
               entry.expandedBig.findViewById(com.android.internal.R.id.media_action_area) != null;
    }

    private void startAppNotificationSettingsActivity(String packageName, int appUid) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        TaskStackBuilder.create(mContext).addNextIntentWithParentStack(intent)
                .startActivities(null, new UserHandle(UserHandle.getUserId(appUid)));
    }

    protected SwipeHelper.LongPressListener getNotificationLongClicker() {
        return new SwipeHelper.LongPressListener() {
            @Override
            public boolean onLongPress(View v, int x, int y) {
                dismissPopups();

                if (v.getWindowToken() == null) return false;

                // Assume we are a status_bar_notification_row
                final View guts = v.findViewById(R.id.notification_guts);
                if (guts == null) return false;

                // Already showing?
                if (guts.getVisibility() == View.VISIBLE) return false;

                guts.setVisibility(View.VISIBLE);
                final double horz = Math.max(v.getWidth() - x, x);
                final double vert = Math.max(v.getHeight() - y, y);
                final float r = (float) Math.hypot(horz, vert);
                final Animator a
                        = ViewAnimationUtils.createCircularReveal(guts, x, y, 0, r);
                a.setDuration(400);
                a.setInterpolator(mLinearOutSlowIn);
                a.start();

                mNotificationGutsExposed = guts;

                return true;
            }
        };
    }

    public void dismissPopups() {
        if (mNotificationGutsExposed != null) {
            final View v = mNotificationGutsExposed;
            mNotificationGutsExposed = null;

            final int x = (v.getLeft() + v.getRight()) / 2;
            final int y = (v.getTop() + v.getBottom()) / 2;
            final Animator a = ViewAnimationUtils.createCircularReveal(v,
                    x, y, x, 0);
            a.setDuration(200);
            a.setInterpolator(mFastOutLinearIn);
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    v.setVisibility(View.GONE);
                }
            });
            a.start();
        }
    }

    public void onHeadsUpDismissed() {
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        int msg = MSG_SHOW_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.obtainMessage(msg, triggeredFromAltTab ? 1 : 0, 0).sendToTarget();
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        int msg = MSG_HIDE_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.obtainMessage(msg, triggeredFromAltTab ? 1 : 0,
                triggeredFromHomeKey ? 1 : 0).sendToTarget();
    }

    @Override
    public void toggleRecentApps() {
        int msg = MSG_TOGGLE_RECENTS_APPS;
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

    /** Jumps to the next affiliated task in the group. */
    public void showNextAffiliatedTask() {
        int msg = MSG_SHOW_NEXT_AFFILIATED_TASK;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    /** Jumps to the previous affiliated task in the group. */
    public void showPreviousAffiliatedTask() {
        int msg = MSG_SHOW_PREV_AFFILIATED_TASK;
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

    protected abstract WindowManager.LayoutParams getSearchLayoutParams(
            LayoutParams layoutParams);

    protected void updateSearchPanel() {
        // Search Panel
        boolean visible = false;
        if (mSearchPanelView != null) {
            visible = mSearchPanelView.isShowing();
            mWindowManager.removeView(mSearchPanelView);
        }

        // Provide SearchPanel with a temporary parent to allow layout params to work.
        LinearLayout tmpRoot = new LinearLayout(mContext);
        mSearchPanelView = (SearchPanelView) LayoutInflater.from(mContext).inflate(
                 R.layout.status_bar_search_panel, tmpRoot, false);
        mSearchPanelView.setOnTouchListener(
                 new TouchOutsideListener(MSG_CLOSE_SEARCH_PANEL, mSearchPanelView));
        mSearchPanelView.setVisibility(View.GONE);

        WindowManager.LayoutParams lp = getSearchLayoutParams(mSearchPanelView.getLayoutParams());

        mWindowManager.addView(mSearchPanelView, lp);
        mSearchPanelView.setBar(this);
        if (visible) {
            mSearchPanelView.show(true, false);
        }
    }

    protected H createHandler() {
         return new H();
    }

    static void sendCloseSystemWindows(Context context, String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    protected abstract View getStatusBarView();

    protected View.OnTouchListener mRecentsPreloadOnTouchListener = new View.OnTouchListener() {
        // additional optimization when we have software system buttons - start loading the recent
        // tasks on touch down
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getAction() & MotionEvent.ACTION_MASK;
            if (action == MotionEvent.ACTION_DOWN) {
                preloadRecents();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                cancelPreloadingRecents();
            } else if (action == MotionEvent.ACTION_UP) {
                if (!v.isPressed()) {
                    cancelPreloadingRecents();
                }

            }
            return false;
        }
    };

    /** Proxy for RecentsComponent */

    protected void showRecents(boolean triggeredFromAltTab) {
        if (mRecents != null) {
            sendCloseSystemWindows(mContext, SYSTEM_DIALOG_REASON_RECENT_APPS);
            mRecents.showRecents(triggeredFromAltTab, getStatusBarView());
        }
    }

    protected void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (mRecents != null) {
            mRecents.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
        }
    }

    protected void toggleRecents() {
        if (mRecents != null) {
            sendCloseSystemWindows(mContext, SYSTEM_DIALOG_REASON_RECENT_APPS);
            mRecents.toggleRecents(mDisplay, mLayoutDirection, getStatusBarView());
        }
    }

    protected void preloadRecents() {
        if (mRecents != null) {
            mRecents.preloadRecents();
        }
    }

    protected void cancelPreloadingRecents() {
        if (mRecents != null) {
            mRecents.cancelPreloadingRecents();
        }
    }

    protected void showRecentsNextAffiliatedTask() {
        if (mRecents != null) {
            mRecents.showNextAffiliatedTask();
        }
    }

    protected void showRecentsPreviousAffiliatedTask() {
        if (mRecents != null) {
            mRecents.showPrevAffiliatedTask();
        }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        // Do nothing
    }

    public abstract void resetHeadsUpDecayTimer();

    public abstract void scheduleHeadsUpOpen();

    public abstract void scheduleHeadsUpClose();

    public abstract void scheduleHeadsUpEscalation();

    /**
     * Save the current "public" (locked and secure) state of the lockscreen.
     */
    public void setLockscreenPublicMode(boolean publicMode) {
        mLockscreenPublicMode = publicMode;
    }

    public boolean isLockscreenPublicMode() {
        return mLockscreenPublicMode;
    }

    /**
     * Has the given user chosen to allow their private (full) notifications to be shown even
     * when the lockscreen is in "public" (secure & locked) mode?
     */
    public boolean userAllowsPrivateNotificationsInPublic(int userHandle) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }

        if (mUsersAllowingPrivateNotifications.indexOfKey(userHandle) < 0) {
            final boolean allowed = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, userHandle);
            final int dpmFlags = mDevicePolicyManager.getKeyguardDisabledFeatures(null /* admin */,
                    userHandle);
            final boolean allowedByDpm = (dpmFlags
                    & DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS) == 0;
            mUsersAllowingPrivateNotifications.append(userHandle, allowed && allowedByDpm);
            return allowed;
        }

        return mUsersAllowingPrivateNotifications.get(userHandle);
    }

    /**
     * Returns true if we're on a secure lockscreen and the user wants to hide "sensitive"
     * notification data. If so, private notifications should show their (possibly
     * auto-generated) publicVersion, and secret notifications should be totally invisible.
     */
    @Override  // NotificationData.Environment
    public boolean shouldHideSensitiveContents(int userid) {
        return isLockscreenPublicMode() && !userAllowsPrivateNotificationsInPublic(userid);
    }

    public void onNotificationClear(StatusBarNotification notification) {
        try {
            mBarService.onNotificationClear(
                    notification.getPackageName(),
                    notification.getTag(),
                    notification.getId(),
                    notification.getUserId());
        } catch (android.os.RemoteException ex) {
            // oh well
        }
    }

    protected class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
             case MSG_SHOW_RECENT_APPS:
                 showRecents(m.arg1 > 0);
                 break;
             case MSG_HIDE_RECENT_APPS:
                 hideRecents(m.arg1 > 0, m.arg2 > 0);
                 break;
             case MSG_TOGGLE_RECENTS_APPS:
                 toggleRecents();
                 break;
             case MSG_PRELOAD_RECENT_APPS:
                  preloadRecents();
                  break;
             case MSG_CANCEL_PRELOAD_RECENT_APPS:
                  cancelPreloadingRecents();
                  break;
             case MSG_SHOW_NEXT_AFFILIATED_TASK:
                  showRecentsNextAffiliatedTask();
                  break;
             case MSG_SHOW_PREV_AFFILIATED_TASK:
                  showRecentsPreviousAffiliatedTask();
                  break;
             case MSG_OPEN_SEARCH_PANEL:
                 if (DEBUG) Log.d(TAG, "opening search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isAssistantAvailable()) {
                     mSearchPanelView.show(true, true);
                 }
                 break;
             case MSG_CLOSE_SEARCH_PANEL:
                 if (DEBUG) Log.d(TAG, "closing search panel");
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

    private boolean inflateViews(NotificationData.Entry entry, ViewGroup parent) {
            return inflateViews(entry, parent, false);
    }

    protected boolean inflateViewsForHeadsUp(NotificationData.Entry entry, ViewGroup parent) {
            return inflateViews(entry, parent, true);
    }

    private boolean inflateViews(NotificationData.Entry entry, ViewGroup parent, boolean isHeadsUp) {
        PackageManager pmUser = getPackageManagerForUser(
                entry.notification.getUser().getIdentifier());

        int maxHeight = mRowMaxHeight;
        StatusBarNotification sbn = entry.notification;
        RemoteViews contentView = sbn.getNotification().contentView;
        RemoteViews bigContentView = sbn.getNotification().bigContentView;

        if (isHeadsUp) {
            maxHeight =
                    mContext.getResources().getDimensionPixelSize(R.dimen.notification_mid_height);
            bigContentView = sbn.getNotification().headsUpContentView;
        }

        if (contentView == null) {
            return false;
        }

        if (DEBUG) {
            Log.v(TAG, "publicNotification: " + sbn.getNotification().publicVersion);
        }

        Notification publicNotification = sbn.getNotification().publicVersion;

        ExpandableNotificationRow row;

        // Stash away previous user expansion state so we can restore it at
        // the end.
        boolean hasUserChangedExpansion = false;
        boolean userExpanded = false;
        boolean userLocked = false;

        if (entry.row != null) {
            row = entry.row;
            hasUserChangedExpansion = row.hasUserChangedExpansion();
            userExpanded = row.isUserExpanded();
            userLocked = row.isUserLocked();
            entry.reset();
            if (hasUserChangedExpansion) {
                row.setUserExpanded(userExpanded);
            }
        } else {
            // create the row view
            LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            row = (ExpandableNotificationRow) inflater.inflate(R.layout.status_bar_notification_row,
                    parent, false);
            row.setExpansionLogger(this, entry.notification.getKey());
        }

        // the notification inspector (see SwipeHelper.setLongPressListener)
        row.setTag(sbn.getPackageName());
        final View guts = row.findViewById(R.id.notification_guts);
        final String pkg = entry.notification.getPackageName();
        String appname = pkg;
        Drawable pkgicon = null;
        int appUid = -1;
        try {
            final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS);
            if (info != null) {
                appname = String.valueOf(pmUser.getApplicationLabel(info));
                pkgicon = pmUser.getApplicationIcon(info);
                appUid = info.uid;
            }
        } catch (NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = pmUser.getDefaultActivityIcon();
        }
        ((ImageView) row.findViewById(android.R.id.icon)).setImageDrawable(pkgicon);
        ((DateTimeView) row.findViewById(R.id.timestamp)).setTime(entry.notification.getPostTime());
        ((TextView) row.findViewById(R.id.pkgname)).setText(appname);
        final View settingsButton = guts.findViewById(R.id.notification_inspect_item);
        if (appUid >= 0) {
            final int appUidF = appUid;
            settingsButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dismissKeyguardThenExecute(new OnDismissAction() {
                        public boolean onDismiss() {
                            startAppNotificationSettingsActivity(pkg, appUidF);
                            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                            visibilityChanged(false);
                            return true;
                        }
                    });
                }
            });
        } else {
            settingsButton.setVisibility(View.GONE);
        }

        workAroundBadLayerDrawableOpacity(row);
        View vetoButton = updateNotificationVetoButton(row, sbn);
        vetoButton.setContentDescription(mContext.getString(
                R.string.accessibility_remove_notification));

        // NB: the large icon is now handled entirely by the template

        // bind the click event to the content area
        NotificationContentView expanded =
                (NotificationContentView) row.findViewById(R.id.expanded);
        NotificationContentView expandedPublic =
                (NotificationContentView) row.findViewById(R.id.expandedPublic);

        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        PendingIntent contentIntent = sbn.getNotification().contentIntent;
        if (contentIntent != null) {
            final View.OnClickListener listener = makeClicker(contentIntent, sbn.getKey(),
                    isHeadsUp);
            row.setOnClickListener(listener);
        } else {
            row.setOnClickListener(null);
        }

        // set up the adaptive layout
        View contentViewLocal = null;
        View bigContentViewLocal = null;
        try {
            contentViewLocal = contentView.apply(mContext, expanded,
                    mOnClickHandler);
            if (bigContentView != null) {
                bigContentViewLocal = bigContentView.apply(mContext, expanded,
                        mOnClickHandler);
            }
        }
        catch (RuntimeException e) {
            final String ident = sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId());
            Log.e(TAG, "couldn't inflate view for notification " + ident, e);
            return false;
        }

        if (contentViewLocal != null) {
            contentViewLocal.setIsRootNamespace(true);
            expanded.setContractedChild(contentViewLocal);
        }
        if (bigContentViewLocal != null) {
            bigContentViewLocal.setIsRootNamespace(true);
            expanded.setExpandedChild(bigContentViewLocal);
        }

        // now the public version
        View publicViewLocal = null;
        if (publicNotification != null) {
            try {
                publicViewLocal = publicNotification.contentView.apply(mContext, expandedPublic,
                        mOnClickHandler);

                if (publicViewLocal != null) {
                    publicViewLocal.setIsRootNamespace(true);
                    expandedPublic.setContractedChild(publicViewLocal);
                }
            }
            catch (RuntimeException e) {
                final String ident = sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId());
                Log.e(TAG, "couldn't inflate public view for notification " + ident, e);
                publicViewLocal = null;
            }
        }

        if (publicViewLocal == null) {
            // Add a basic notification template
            publicViewLocal = LayoutInflater.from(mContext).inflate(
                    com.android.internal.R.layout.notification_template_material_base,
                    expandedPublic, true);

            final TextView title = (TextView) publicViewLocal.findViewById(com.android.internal.R.id.title);
            try {
                title.setText(pmUser.getApplicationLabel(
                        pmUser.getApplicationInfo(entry.notification.getPackageName(), 0)));
            } catch (NameNotFoundException e) {
                title.setText(entry.notification.getPackageName());
            }

            final ImageView icon = (ImageView) publicViewLocal.findViewById(
                    com.android.internal.R.id.icon);
            final ImageView profileIcon = (ImageView) publicViewLocal.findViewById(
                    com.android.internal.R.id.profile_icon);

            final StatusBarIcon ic = new StatusBarIcon(entry.notification.getPackageName(),
                    entry.notification.getUser(),
                    entry.notification.getNotification().icon,
                    entry.notification.getNotification().iconLevel,
                    entry.notification.getNotification().number,
                    entry.notification.getNotification().tickerText);

            Drawable iconDrawable = StatusBarIconView.getIcon(mContext, ic);
            icon.setImageDrawable(iconDrawable);
            if (mNotificationColorUtil.isGrayscale(iconDrawable)) {
                icon.setBackgroundResource(
                        com.android.internal.R.drawable.notification_icon_legacy_bg_inset);
            }

            if (profileIcon != null) {
                Drawable profileDrawable
                        = mUserManager.getBadgeForUser(entry.notification.getUser(), 0);
                if (profileDrawable != null) {
                    profileIcon.setImageDrawable(profileDrawable);
                    profileIcon.setVisibility(View.VISIBLE);
                } else {
                    profileIcon.setVisibility(View.GONE);
                }
            }

            final View privateTime = contentViewLocal.findViewById(com.android.internal.R.id.time);
            if (privateTime != null && privateTime.getVisibility() == View.VISIBLE) {
                final View timeStub = publicViewLocal.findViewById(com.android.internal.R.id.time);
                timeStub.setVisibility(View.VISIBLE);
                final DateTimeView dateTimeView = (DateTimeView)
                        publicViewLocal.findViewById(com.android.internal.R.id.time);
                dateTimeView.setTime(entry.notification.getNotification().when);
            }

            final TextView text = (TextView) publicViewLocal.findViewById(
                com.android.internal.R.id.text);
            if (text != null) {
                text.setText(R.string.notification_hidden_text);
                text.setTextAppearance(mContext,
                        R.style.TextAppearance_StatusBar_Material_EventContent_Parenthetical);
            }

            entry.autoRedacted = true;
        }

        row.setClearable(sbn.isClearable());

        if (MULTIUSER_DEBUG) {
            TextView debug = (TextView) row.findViewById(R.id.debug_info);
            if (debug != null) {
                debug.setVisibility(View.VISIBLE);
                debug.setText("CU " + mCurrentUserId +" NU " + entry.notification.getUserId());
            }
        }
        entry.row = row;
        entry.row.setHeightRange(mRowMinHeight, maxHeight);
        entry.row.setOnActivatedListener(this);
        entry.expanded = contentViewLocal;
        entry.expandedPublic = publicViewLocal;
        entry.setBigContentView(bigContentViewLocal);

        applyLegacyRowBackground(sbn, entry);

        // Restore previous flags.
        if (hasUserChangedExpansion) {
            // Note: setUserExpanded() conveniently ignores calls with
            //       userExpanded=true if !isExpandable().
            row.setUserExpanded(userExpanded);
        }
        row.setUserLocked(userLocked);

        return true;
    }

    public NotificationClicker makeClicker(PendingIntent intent, String notificationKey,
            boolean forHun) {
        return new NotificationClicker(intent, notificationKey, forHun);
    }

    protected class NotificationClicker implements View.OnClickListener {
        private PendingIntent mIntent;
        private final String mNotificationKey;
        private boolean mIsHeadsUp;

        public NotificationClicker(PendingIntent intent, String notificationKey, boolean forHun) {
            mIntent = intent;
            mNotificationKey = notificationKey;
            mIsHeadsUp = forHun;
        }

        public void onClick(final View v) {
            final boolean keyguardShowing = mStatusBarKeyguardViewManager.isShowing();
            dismissKeyguardThenExecute(new OnDismissAction() {
                public boolean onDismiss() {
                    if (mIsHeadsUp) {
                        mHeadsUpNotificationView.clear();
                    }
                    AsyncTask.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (keyguardShowing) {
                                try {
                                    ActivityManagerNative.getDefault()
                                            .keyguardWaitingForActivityDrawn();
                                    // The intent we are sending is for the application, which
                                    // won't have permission to immediately start an activity after
                                    // the user switches to home.  We know it is safe to do at this
                                    // point, so make sure new activity switches are now allowed.
                                    ActivityManagerNative.getDefault().resumeAppSwitches();
                                } catch (RemoteException e) {
                                }
                            }

                            if (mIntent != null) {
                                try {
                                    mIntent.send();
                                } catch (PendingIntent.CanceledException e) {
                                    // the stack trace isn't very helpful here.
                                    // Just log the exception message.
                                    Log.w(TAG, "Sending contentIntent failed: " + e);

                                    // TODO: Dismiss Keyguard.
                                }
                                if (mIntent.isActivity()) {
                                    overrideActivityPendingAppTransition(keyguardShowing);
                                }
                            }

                            try {
                                mBarService.onNotificationClick(mNotificationKey);
                            } catch (RemoteException ex) {
                                // system process is dead if we're here.
                            }
                        }
                    });

                    // close the shade if it was open
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
                    visibilityChanged(false);

                    return mIntent != null && mIntent.isActivity();
                }
            });
        }
    }

    public void animateCollapsePanels(int flags, boolean force) {
    }

    public void overrideActivityPendingAppTransition(boolean keyguardShowing) {
        if (keyguardShowing) {
            try {
                mWindowManagerService.overridePendingAppTransition(null, 0, 0, null);
            } catch (RemoteException e) {
                Log.w(TAG, "Error overriding app transition: " + e);
            }
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
            if (!visible) {
                dismissPopups();
            }
            try {
                if (visible) {
                    mBarService.onPanelRevealed();
                } else {
                    mBarService.onPanelHidden();
                }
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
            }
        }
    }

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    void handleNotificationError(StatusBarNotification n, String message) {
        removeNotification(n.getKey(), null);
        try {
            mBarService.onNotificationError(n.getPackageName(), n.getTag(), n.getId(), n.getUid(),
                    n.getInitialPid(), message, n.getUserId());
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    protected StatusBarNotification removeNotificationViews(String key, RankingMap ranking) {
        NotificationData.Entry entry = mNotificationData.remove(key, ranking);
        if (entry == null) {
            Log.w(TAG, "removeNotification for unknown key: " + key);
            return null;
        }
        updateNotifications();
        return entry.notification;
    }

    protected NotificationData.Entry createNotificationViews(StatusBarNotification sbn) {
        if (DEBUG) {
            Log.d(TAG, "createNotificationViews(notification=" + sbn);
        }
        // Construct the icon.
        Notification n = sbn.getNotification();
        final StatusBarIconView iconView = new StatusBarIconView(mContext,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), n);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        final StatusBarIcon ic = new StatusBarIcon(sbn.getPackageName(),
                sbn.getUser(),
                    n.icon,
                    n.iconLevel,
                    n.number,
                    n.tickerText);
        if (!iconView.set(ic)) {
            handleNotificationError(sbn, "Couldn't create icon: " + ic);
            return null;
        }
        // Construct the expanded view.
        NotificationData.Entry entry = new NotificationData.Entry(sbn, iconView);
        if (!inflateViews(entry, mStackScroller)) {
            handleNotificationError(sbn, "Couldn't expand RemoteViews for: " + sbn);
            return null;
        }
        return entry;
    }

    protected void addNotificationViews(Entry entry, RankingMap ranking) {
        if (entry == null) {
            return;
        }
        // Add the expanded view and icon.
        mNotificationData.add(entry, ranking);
        updateNotifications();
    }

    /**
     * @return The number of notifications we show on Keyguard.
     */
    protected abstract int getMaxKeyguardNotifications();

    /**
     * Updates expanded, dimmed and locked states of notification rows.
     */
    protected void updateRowStates() {
        int maxKeyguardNotifications = getMaxKeyguardNotifications();
        mKeyguardIconOverflowContainer.getIconsView().removeAllViews();

        ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
        final int N = activeNotifications.size();

        int visibleNotifications = 0;
        boolean onKeyguard = mState == StatusBarState.KEYGUARD;
        for (int i = 0; i < N; i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
            if (onKeyguard) {
                entry.row.setExpansionDisabled(true);
            } else {
                entry.row.setExpansionDisabled(false);
                if (!entry.row.isUserLocked()) {
                    boolean top = (i == 0);
                    entry.row.setSystemExpanded(top);
                }
            }
            boolean showOnKeyguard = shouldShowOnKeyguard(entry.notification);
            if (onKeyguard && (visibleNotifications >= maxKeyguardNotifications
                    || !showOnKeyguard)) {
                entry.row.setVisibility(View.GONE);
                if (showOnKeyguard) {
                    mKeyguardIconOverflowContainer.getIconsView().addNotification(entry);
                }
            } else {
                boolean wasGone = entry.row.getVisibility() == View.GONE;
                entry.row.setVisibility(View.VISIBLE);
                if (wasGone) {
                    // notify the scroller of a child addition
                    mStackScroller.generateAddAnimation(entry.row, true /* fromMoreCard */);
                }
                visibleNotifications++;
            }
        }

        if (onKeyguard && mKeyguardIconOverflowContainer.getIconsView().getChildCount() > 0) {
            mKeyguardIconOverflowContainer.setVisibility(View.VISIBLE);
        } else {
            mKeyguardIconOverflowContainer.setVisibility(View.GONE);
        }

        mStackScroller.changeViewPosition(mKeyguardIconOverflowContainer,
                mStackScroller.getChildCount() - 3);
        mStackScroller.changeViewPosition(mEmptyShadeView, mStackScroller.getChildCount() - 2);
        mStackScroller.changeViewPosition(mDismissView, mStackScroller.getChildCount() - 1);
    }

    private boolean shouldShowOnKeyguard(StatusBarNotification sbn) {
        return mShowLockscreenNotifications && !mNotificationData.isAmbient(sbn.getKey());
    }

    protected void setZenMode(int mode) {
        if (!isDeviceProvisioned()) return;
        mZenMode = mode;
        updateNotifications();
    }

    // extended in PhoneStatusBar
    protected void setShowLockscreenNotifications(boolean show) {
        mShowLockscreenNotifications = show;
    }

    private void updateLockscreenNotificationSetting() {
        final boolean show = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                1,
                mCurrentUserId) != 0;
        final int dpmFlags = mDevicePolicyManager.getKeyguardDisabledFeatures(
                null /* admin */, mCurrentUserId);
        final boolean allowedByDpm = (dpmFlags
                & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS) == 0;
        setShowLockscreenNotifications(show && allowedByDpm);
    }

    protected abstract void haltTicker();
    protected abstract void setAreThereNotifications();
    protected abstract void updateNotifications();
    protected abstract void tick(StatusBarNotification n, boolean firstTime);
    protected abstract void updateExpandedViewPos(int expandedPosition);
    protected abstract boolean shouldDisableNavbarGestures();

    public abstract void addNotification(StatusBarNotification notification,
            RankingMap ranking);
    protected abstract void updateNotificationRanking(RankingMap ranking);
    public abstract void removeNotification(String key, RankingMap ranking);

    public void updateNotification(StatusBarNotification notification, RankingMap ranking) {
        if (DEBUG) Log.d(TAG, "updateNotification(" + notification + ")");

        final String key = notification.getKey();
        boolean wasHeadsUp = isHeadsUp(key);
        Entry oldEntry;
        if (wasHeadsUp) {
            oldEntry = mHeadsUpNotificationView.getEntry();
        } else {
            oldEntry = mNotificationData.get(key);
        }
        if (oldEntry == null) {
            return;
        }

        final StatusBarNotification oldNotification = oldEntry.notification;

        // XXX: modify when we do something more intelligent with the two content views
        final RemoteViews oldContentView = oldNotification.getNotification().contentView;
        Notification n = notification.getNotification();
        final RemoteViews contentView = n.contentView;
        final RemoteViews oldBigContentView = oldNotification.getNotification().bigContentView;
        final RemoteViews bigContentView = n.bigContentView;
        final RemoteViews oldHeadsUpContentView = oldNotification.getNotification().headsUpContentView;
        final RemoteViews headsUpContentView = n.headsUpContentView;
        final Notification oldPublicNotification = oldNotification.getNotification().publicVersion;
        final RemoteViews oldPublicContentView = oldPublicNotification != null
                ? oldPublicNotification.contentView : null;
        final Notification publicNotification = n.publicVersion;
        final RemoteViews publicContentView = publicNotification != null
                ? publicNotification.contentView : null;

        if (DEBUG) {
            Log.d(TAG, "old notification: when=" + oldNotification.getNotification().when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " expanded=" + oldEntry.expanded
                    + " contentView=" + oldContentView
                    + " bigContentView=" + oldBigContentView
                    + " publicView=" + oldPublicContentView
                    + " rowParent=" + oldEntry.row.getParent());
            Log.d(TAG, "new notification: when=" + n.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " contentView=" + contentView
                    + " bigContentView=" + bigContentView
                    + " publicView=" + publicContentView);
        }

        // Can we just reapply the RemoteViews in place?

        // 1U is never null
        boolean contentsUnchanged = oldEntry.expanded != null
                && contentView.getPackage() != null
                && oldContentView.getPackage() != null
                && oldContentView.getPackage().equals(contentView.getPackage())
                && oldContentView.getLayoutId() == contentView.getLayoutId();
        // large view may be null
        boolean bigContentsUnchanged =
                (oldEntry.getBigContentView() == null && bigContentView == null)
                || ((oldEntry.getBigContentView() != null && bigContentView != null)
                    && bigContentView.getPackage() != null
                    && oldBigContentView.getPackage() != null
                    && oldBigContentView.getPackage().equals(bigContentView.getPackage())
                    && oldBigContentView.getLayoutId() == bigContentView.getLayoutId());
        boolean headsUpContentsUnchanged =
                (oldHeadsUpContentView == null && headsUpContentView == null)
                || ((oldHeadsUpContentView != null && headsUpContentView != null)
                    && headsUpContentView.getPackage() != null
                    && oldHeadsUpContentView.getPackage() != null
                    && oldHeadsUpContentView.getPackage().equals(headsUpContentView.getPackage())
                    && oldHeadsUpContentView.getLayoutId() == headsUpContentView.getLayoutId());
        boolean publicUnchanged  =
                (oldPublicContentView == null && publicContentView == null)
                || ((oldPublicContentView != null && publicContentView != null)
                        && publicContentView.getPackage() != null
                        && oldPublicContentView.getPackage() != null
                        && oldPublicContentView.getPackage().equals(publicContentView.getPackage())
                        && oldPublicContentView.getLayoutId() == publicContentView.getLayoutId());
        boolean updateTicker = n.tickerText != null
                && !TextUtils.equals(n.tickerText,
                oldEntry.notification.getNotification().tickerText);

        final boolean shouldInterrupt = shouldInterrupt(notification);
        final boolean alertAgain = alertAgain(oldEntry);
        boolean updateSuccessful = false;
        if (contentsUnchanged && bigContentsUnchanged && headsUpContentsUnchanged
                && publicUnchanged) {
            if (DEBUG) Log.d(TAG, "reusing notification for key: " + key);
            oldEntry.notification = notification;
            try {
                if (oldEntry.icon != null) {
                    // Update the icon
                    final StatusBarIcon ic = new StatusBarIcon(notification.getPackageName(),
                            notification.getUser(),
                            n.icon,
                            n.iconLevel,
                            n.number,
                            n.tickerText);
                    oldEntry.icon.setNotification(n);
                    if (!oldEntry.icon.set(ic)) {
                        handleNotificationError(notification, "Couldn't update icon: " + ic);
                        return;
                    }
                }

                if (wasHeadsUp) {
                    if (shouldInterrupt) {
                        updateHeadsUpViews(oldEntry, notification);
                        if (alertAgain) {
                            resetHeadsUpDecayTimer();
                        }
                    } else {
                        // we updated the notification above, so release to build a new shade entry
                        mHeadsUpNotificationView.releaseAndClose();
                        return;
                    }
                } else {
                    if (shouldInterrupt && alertAgain) {
                        removeNotificationViews(key, ranking);
                        addNotification(notification, ranking);  //this will pop the headsup
                    } else {
                        updateNotificationViews(oldEntry, notification);
                    }
                }
                mNotificationData.updateRanking(ranking);
                updateNotifications();
                updateSuccessful = true;
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Log.w(TAG, "Couldn't reapply views for package " + contentView.getPackage(), e);
            }
        }
        if (!updateSuccessful) {
            if (DEBUG) Log.d(TAG, "not reusing notification for key: " + key);
            if (wasHeadsUp) {
                if (shouldInterrupt) {
                    if (DEBUG) Log.d(TAG, "rebuilding heads up for key: " + key);
                    Entry newEntry = new Entry(notification, null);
                    ViewGroup holder = mHeadsUpNotificationView.getHolder();
                    if (inflateViewsForHeadsUp(newEntry, holder)) {
                        mHeadsUpNotificationView.showNotification(newEntry);
                        if (alertAgain) {
                            resetHeadsUpDecayTimer();
                        }
                    } else {
                        Log.w(TAG, "Couldn't create new updated headsup for package "
                                + contentView.getPackage());
                    }
                } else {
                    if (DEBUG) Log.d(TAG, "releasing heads up for key: " + key);
                    oldEntry.notification = notification;
                    mHeadsUpNotificationView.releaseAndClose();
                    return;
                }
            } else {
                if (shouldInterrupt && alertAgain) {
                    if (DEBUG) Log.d(TAG, "reposting to invoke heads up for key: " + key);
                    removeNotificationViews(key, ranking);
                    addNotification(notification, ranking);  //this will pop the headsup
                } else {
                    if (DEBUG) Log.d(TAG, "rebuilding update in place for key: " + key);
                    oldEntry.notification = notification;
                    final StatusBarIcon ic = new StatusBarIcon(notification.getPackageName(),
                            notification.getUser(),
                            n.icon,
                            n.iconLevel,
                            n.number,
                            n.tickerText);
                    oldEntry.icon.setNotification(n);
                    oldEntry.icon.set(ic);
                    inflateViews(oldEntry, mStackScroller, wasHeadsUp);
                    mNotificationData.updateRanking(ranking);
                    updateNotifications();
                }
            }
        }

        // Update the veto button accordingly (and as a result, whether this row is
        // swipe-dismissable)
        updateNotificationVetoButton(oldEntry.row, notification);

        // Is this for you?
        boolean isForCurrentUser = isNotificationForCurrentProfiles(notification);
        if (DEBUG) Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");

        // Restart the ticker if it's still running
        if (updateTicker && isForCurrentUser) {
            haltTicker();
            tick(notification, false);
        }

        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    private void updateNotificationViews(NotificationData.Entry entry,
            StatusBarNotification notification) {
        updateNotificationViews(entry, notification, false);
    }

    private void updateHeadsUpViews(NotificationData.Entry entry,
            StatusBarNotification notification) {
        updateNotificationViews(entry, notification, true);
    }

    private void updateNotificationViews(NotificationData.Entry entry,
            StatusBarNotification notification, boolean isHeadsUp) {
        final RemoteViews contentView = notification.getNotification().contentView;
        final RemoteViews bigContentView = isHeadsUp
                ? notification.getNotification().headsUpContentView
                : notification.getNotification().bigContentView;
        final Notification publicVersion = notification.getNotification().publicVersion;
        final RemoteViews publicContentView = publicVersion != null ? publicVersion.contentView
                : null;

        // Reapply the RemoteViews
        if (entry.row != null) {
            entry.row.resetHeight();
        }
        contentView.reapply(mContext, entry.expanded, mOnClickHandler);
        if (bigContentView != null && entry.getBigContentView() != null) {
            bigContentView.reapply(mContext, entry.getBigContentView(),
                    mOnClickHandler);
        }
        if (publicContentView != null && entry.getPublicContentView() != null) {
            publicContentView.reapply(mContext, entry.getPublicContentView(), mOnClickHandler);
        }
        // update the contentIntent
        final PendingIntent contentIntent = notification.getNotification().contentIntent;
        if (contentIntent != null) {
            final View.OnClickListener listener = makeClicker(contentIntent, notification.getKey(),
                    isHeadsUp);
            entry.row.setOnClickListener(listener);
        } else {
            entry.row.setOnClickListener(null);
        }
        entry.row.notifyContentUpdated();
    }

    protected void notifyHeadsUpScreenOn(boolean screenOn) {
        if (!screenOn) {
            scheduleHeadsUpEscalation();
        }
    }

    private boolean alertAgain(Entry entry) {
        final StatusBarNotification sbn = entry.notification;
        return entry == null || !entry.hasInterrupted()
                || (sbn.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0;
    }

    protected boolean shouldInterrupt(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        // some predicates to make the boolean logic legible
        boolean isNoisy = (notification.defaults & Notification.DEFAULT_SOUND) != 0
                || (notification.defaults & Notification.DEFAULT_VIBRATE) != 0
                || notification.sound != null
                || notification.vibrate != null;
        boolean isHighPriority = sbn.getScore() >= INTERRUPTION_THRESHOLD;
        boolean isFullscreen = notification.fullScreenIntent != null;
        boolean hasTicker = mHeadsUpTicker && !TextUtils.isEmpty(notification.tickerText);
        boolean isAllowed = notification.extras.getInt(Notification.EXTRA_AS_HEADS_UP,
                Notification.HEADS_UP_ALLOWED) != Notification.HEADS_UP_NEVER;

        final KeyguardTouchDelegate keyguard = KeyguardTouchDelegate.getInstance(mContext);
        boolean interrupt = (isFullscreen || (isHighPriority && (isNoisy || hasTicker)))
                && isAllowed
                && mPowerManager.isScreenOn()
                && !keyguard.isShowingAndNotOccluded()
                && !keyguard.isInputRestricted();
        try {
            interrupt = interrupt && !mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.d(TAG, "failed to query dream manager", e);
        }
        if (DEBUG) Log.d(TAG, "interrupt: " + interrupt);
        return interrupt;
    }

    public boolean inKeyguardRestrictedInputMode() {
        return KeyguardTouchDelegate.getInstance(mContext).isInputRestricted();
    }

    public void setInteracting(int barWindow, boolean interacting) {
        // hook for subclasses
    }

    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    public boolean isBouncerShowing() {
        return mBouncerShowing;
    }

    public void destroy() {
        if (mSearchPanelView != null) {
            mWindowManager.removeViewImmediate(mSearchPanelView);
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    /**
     * @return a PackageManger for userId or if userId is < 0 (USER_ALL etc) then
     *         return PackageManager for mContext
     */
    protected PackageManager getPackageManagerForUser(int userId) {
        Context contextForUser = mContext;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        mContext.createPackageContextAsUser(mContext.getPackageName(),
                        Context.CONTEXT_RESTRICTED,
                        new UserHandle(userId));
            } catch (NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    @Override
    public void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        try {
            mBarService.onNotificationExpansionChanged(key, userAction, expanded);
        } catch (RemoteException e) {
            // Ignore.
        }
    }
}
