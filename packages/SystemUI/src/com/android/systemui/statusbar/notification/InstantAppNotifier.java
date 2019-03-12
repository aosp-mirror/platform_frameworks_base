/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SynchronousUserSwitchObserver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.Dependency;
import com.android.systemui.DockedStackExistsListener;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.SystemUI;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.util.NotificationChannels;

import java.util.List;

/** The clsss to show notification(s) of instant apps. This may show multiple notifications on
 * splitted screen.
 */
public class InstantAppNotifier extends SystemUI
        implements CommandQueue.Callbacks, KeyguardMonitor.Callback {
    private static final String TAG = "InstantAppNotifier";
    public static final int NUM_TASKS_FOR_INSTANT_APP_INFO = 5;

    private final Handler mHandler = new Handler();
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);
    private final ArraySet<Pair<String, Integer>> mCurrentNotifs = new ArraySet<>();
    private boolean mDockedStackExists;
    private KeyguardMonitor mKeyguardMonitor;

    public InstantAppNotifier() {}

    @Override
    public void start() {
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);

        // listen for user / profile change.
        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchListener, TAG);
        } catch (RemoteException e) {
            // Ignore
        }

        SysUiServiceProvider.getComponent(mContext, CommandQueue.class).addCallback(this);
        mKeyguardMonitor.addCallback(this);

        DockedStackExistsListener.register(
                exists -> {
                    mDockedStackExists = exists;
                    updateForegroundInstantApps();
                });

        // Clear out all old notifications on startup (only present in the case where sysui dies)
        NotificationManager noMan = mContext.getSystemService(NotificationManager.class);
        for (StatusBarNotification notification : noMan.getActiveNotifications()) {
            if (notification.getId() == SystemMessage.NOTE_INSTANT_APPS) {
                noMan.cancel(notification.getTag(), notification.getId());
            }
        }
    }

    @Override
    public void appTransitionStarting(
            int displayId, long startTime, long duration, boolean forced) {
        if (mContext.getDisplayId() == displayId) {
            updateForegroundInstantApps();
        }
    }

    @Override
    public void onKeyguardShowingChanged() {
        updateForegroundInstantApps();
    }

    @Override
    public void preloadRecentApps() {
        updateForegroundInstantApps();
    }

    private final SynchronousUserSwitchObserver mUserSwitchListener =
            new SynchronousUserSwitchObserver() {
                @Override
                public void onUserSwitching(int newUserId) throws RemoteException {}

                @Override
                public void onUserSwitchComplete(int newUserId) throws RemoteException {
                    mHandler.post(
                            () -> {
                                updateForegroundInstantApps();
                            });
                }
            };

    private final TaskStackChangeListener mTaskListener =
            new TaskStackChangeListener() {
                @Override
                public void onTaskStackChanged() {
                    // Listen for changes to stacks and then check which instant apps are
                    // foreground.
                    updateForegroundInstantApps();
                }
            };

    private void updateForegroundInstantApps() {
        NotificationManager noMan = mContext.getSystemService(NotificationManager.class);
        IPackageManager pm = AppGlobals.getPackageManager();
        mUiOffloadThread.submit(
                () -> {
                    ArraySet<Pair<String, Integer>> notifs = new ArraySet<>(mCurrentNotifs);
                    try {
                        final ActivityManager.StackInfo focusedStack =
                                ActivityTaskManager.getService().getFocusedStackInfo();
                        if (focusedStack != null) {
                            final int windowingMode =
                                    focusedStack.configuration.windowConfiguration
                                            .getWindowingMode();
                            if (windowingMode == WINDOWING_MODE_FULLSCREEN
                                    || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                                checkAndPostForStack(focusedStack, notifs, noMan, pm);
                            }
                        }
                        if (mDockedStackExists) {
                            checkAndPostForPrimaryScreen(notifs, noMan, pm);
                        }
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }

                    // Cancel all the leftover notifications that don't have a foreground
                    // process anymore.
                    notifs.forEach(
                            v -> {
                                mCurrentNotifs.remove(v);

                                noMan.cancelAsUser(
                                        v.first,
                                        SystemMessageProto.SystemMessage.NOTE_INSTANT_APPS,
                                        new UserHandle(v.second));
                            });
                });
    }

    /**
     * Posts an instant app notification if the top activity of the primary container in the
     * splitted screen is an instant app and the corresponding instant app notification is not
     * posted yet. If the notification already exists, this method removes it from {@code
     * notifs} in the arguments.
     */
    private void checkAndPostForPrimaryScreen(
            @NonNull ArraySet<Pair<String, Integer>> notifs,
            @NonNull NotificationManager noMan,
            @NonNull IPackageManager pm) {
        try {
            final ActivityManager.StackInfo info =
                    ActivityTaskManager.getService()
                            .getStackInfo(
                                    WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_UNDEFINED);
            checkAndPostForStack(info, notifs, noMan, pm);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Posts an instant app notification if the top activity of the given stack is an instant app
     * and the corresponding instant app notification is not posted yet. If the notification already
     * exists, this method removes it from {@code notifs} in the arguments.
     */
    private void checkAndPostForStack(
            @Nullable ActivityManager.StackInfo info,
            @NonNull ArraySet<Pair<String, Integer>> notifs,
            @NonNull NotificationManager noMan,
            @NonNull IPackageManager pm) {
        try {
            if (info == null || info.topActivity == null) return;
            String pkg = info.topActivity.getPackageName();
            Pair<String, Integer> key = new Pair<>(pkg, info.userId);
            if (!notifs.remove(key)) {
                // TODO: Optimize by not always needing to get application info.
                // Maybe cache non-instant-app packages?
                ApplicationInfo appInfo =
                        pm.getApplicationInfo(
                                pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, info.userId);
                if (appInfo.isInstantApp()) {
                    postInstantAppNotif(
                            pkg,
                            info.userId,
                            appInfo,
                            noMan,
                            info.taskIds[info.taskIds.length - 1]);
                }
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Posts an instant app notification. */
    private void postInstantAppNotif(
            @NonNull String pkg,
            int userId,
            @NonNull ApplicationInfo appInfo,
            @NonNull NotificationManager noMan,
            int taskId) {
        final Bundle extras = new Bundle();
        extras.putString(
                Notification.EXTRA_SUBSTITUTE_APP_NAME, mContext.getString(R.string.instant_apps));
        mCurrentNotifs.add(new Pair<>(pkg, userId));

        String helpUrl = mContext.getString(R.string.instant_apps_help_url);
        boolean hasHelpUrl = !helpUrl.isEmpty();
        String message =
                mContext.getString(
                        hasHelpUrl
                                ? R.string.instant_apps_message_with_help
                                : R.string.instant_apps_message);

        UserHandle user = UserHandle.of(userId);
        PendingIntent appInfoAction =
                PendingIntent.getActivityAsUser(
                        mContext,
                        0,
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", pkg, null)),
                        0,
                        null,
                        user);
        Notification.Action action =
                new Notification.Action.Builder(
                                null, mContext.getString(R.string.app_info), appInfoAction)
                        .build();
        PendingIntent helpCenterIntent =
                hasHelpUrl
                        ? PendingIntent.getActivityAsUser(
                                mContext,
                                0,
                                new Intent(Intent.ACTION_VIEW).setData(Uri.parse(helpUrl)),
                                0,
                                null,
                                user)
                        : null;

        Intent browserIntent = getTaskIntent(taskId, userId);
        Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.GENERAL);
        if (browserIntent != null && browserIntent.isWebIntent()) {
            // Make sure that this doesn't resolve back to an instant app
            browserIntent
                    .setComponent(null)
                    .setPackage(null)
                    .addFlags(Intent.FLAG_IGNORE_EPHEMERAL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            PendingIntent pendingIntent =
                    PendingIntent.getActivityAsUser(
                            mContext,
                            0 /* requestCode */,
                            browserIntent,
                            0 /* flags */,
                            null,
                            user);
            ComponentName aiaComponent = null;
            try {
                aiaComponent = AppGlobals.getPackageManager().getInstantAppInstallerComponent();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            Intent goToWebIntent =
                    new Intent()
                            .setComponent(aiaComponent)
                            .setAction(Intent.ACTION_VIEW)
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addCategory("unique:" + System.currentTimeMillis())
                            .putExtra(Intent.EXTRA_PACKAGE_NAME, appInfo.packageName)
                            .putExtra(
                                    Intent.EXTRA_VERSION_CODE,
                                    (int) (appInfo.versionCode & 0x7fffffff))
                            .putExtra(Intent.EXTRA_LONG_VERSION_CODE, appInfo.versionCode)
                            .putExtra(Intent.EXTRA_INSTANT_APP_FAILURE, pendingIntent);

            PendingIntent webPendingIntent =
                    PendingIntent.getActivityAsUser(mContext, 0, goToWebIntent, 0, null, user);
            Notification.Action webAction =
                    new Notification.Action.Builder(
                                    null, mContext.getString(R.string.go_to_web), webPendingIntent)
                            .build();
            builder.addAction(webAction);
        }

        noMan.notifyAsUser(
                pkg,
                SystemMessage.NOTE_INSTANT_APPS,
                builder.addExtras(extras)
                        .addAction(action)
                        .setContentIntent(helpCenterIntent)
                        .setColor(mContext.getColor(R.color.instant_apps_color))
                        .setContentTitle(
                                mContext.getString(
                                        R.string.instant_apps_title,
                                        appInfo.loadLabel(mContext.getPackageManager())))
                        .setLargeIcon(Icon.createWithResource(pkg, appInfo.icon))
                        .setSmallIcon(
                                Icon.createWithResource(
                                        mContext.getPackageName(), R.drawable.instant_icon))
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setOngoing(true)
                        .build(),
                new UserHandle(userId));
    }

    @Nullable
    private Intent getTaskIntent(int taskId, int userId) {
        try {
            final List<ActivityManager.RecentTaskInfo> tasks =
                    ActivityTaskManager.getService()
                            .getRecentTasks(NUM_TASKS_FOR_INSTANT_APP_INFO, 0, userId)
                            .getList();
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id == taskId) {
                    return tasks.get(i).baseIntent;
                }
            }
        } catch (RemoteException e) {
            // Fall through
        }
        return null;
    }
}
