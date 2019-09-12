package com.google.android.systemui.statusbar;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.google.android.collect.Sets;
import java.util.HashSet;

public class NotificationEntryManagerGoogle extends NotificationEntryManager {
    private static final HashSet<String> NOTIFYABLE_PACKAGES = Sets.newHashSet(new String[]{"com.breel.wallpapers", "com.breel.wallpapers18", "com.google.pixel.livewallpaper"});
    private static final String[] NOTIFYABLE_WALLPAPERS = {"com.breel.wallpapers.imprint", "com.breel.wallpapers18.tactile", "com.breel.wallpapers18.delight", "com.breel.wallpapers18.miniman", "com.google.pixel.livewallpaper.imprint", "com.google.pixel.livewallpaper.tactile", "com.google.pixel.livewallpaper.delight", "com.google.pixel.livewallpaper.miniman"};
    private final Context mContext;
    private boolean mShouldBroadcastNotifications;
    private final CurrentUserTracker mUserTracker;
    private BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.intent.action.WALLPAPER_CHANGED")) {
                checkNotificationBroadcastSupport();
            }
        }
    };
    private String mWallpaperPackage;

    public NotificationEntryManagerGoogle(Context context) {
        super(context);
        mContext = context;
        mUserTracker = new CurrentUserTracker(context) {
            @Override
            public void onUserSwitched(int i) {
            }
        };
    }

    @Override
    public void setUpWithPresenter(NotificationPresenter notificationPresenter, NotificationListContainer notificationListContainer, HeadsUpManager headsUpManager) {
        super.setUpWithPresenter(notificationPresenter, notificationListContainer, headsUpManager);
        mContext.registerReceiver(mWallpaperChangedReceiver, new IntentFilter("android.intent.action.WALLPAPER_CHANGED"));
        checkNotificationBroadcastSupport();
    }

    @Override
    public void addNotification(StatusBarNotification statusBarNotification, RankingMap rankingMap) {
        super.addNotification(statusBarNotification, rankingMap);
        if (mShouldBroadcastNotifications && mUserTracker.getCurrentUserId() == 0) {
            Intent intent = new Intent();
            intent.setPackage(mWallpaperPackage);
            intent.setAction("com.breel.wallpapers.NOTIFICATION_RECEIVED");
            intent.putExtra("notification_color", statusBarNotification.getNotification().color);
            mContext.sendBroadcast(intent, "com.breel.wallpapers.notifications");
        }
    }

    private void checkNotificationBroadcastSupport() {
        mShouldBroadcastNotifications = false;
        WallpaperManager wallpaperManager = (WallpaperManager) mContext.getSystemService(WallpaperManager.class);
        WallpaperInfo wallpaperInfo = wallpaperManager != null ? wallpaperManager.getWallpaperInfo() : null;
        if (wallpaperInfo != null) {
            ComponentName component = wallpaperInfo.getComponent();
            String packageName = component.getPackageName();
            if (NOTIFYABLE_PACKAGES.contains(packageName)) {
                mWallpaperPackage = packageName;
                String className = component.getClassName();
                String[] strArr = NOTIFYABLE_WALLPAPERS;
                int length = strArr.length;
                int i = 0;
                while (true) {
                    if (i >= length) {
                        break;
                    } else if (className.startsWith(strArr[i])) {
                        mShouldBroadcastNotifications = true;
                        break;
                    } else {
                        i++;
                    }
                }
            }
        }
    }
}
