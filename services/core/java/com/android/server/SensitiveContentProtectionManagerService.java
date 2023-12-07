/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerInternal;

import java.util.Collections;
import java.util.Set;

/**
 * Service that monitors for notifications with sensitive content and protects content from screen
 * sharing
 */
public final class SensitiveContentProtectionManagerService extends SystemService {
    private static final String TAG = "SensitiveContentProtect";
    private static final boolean DEBUG = false;

    @VisibleForTesting
    NotificationListener mNotificationListener;
    private @Nullable MediaProjectionManager mProjectionManager;
    private @Nullable WindowManagerInternal mWindowManager;

    private final MediaProjectionManager.Callback mProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    if (DEBUG) Log.d(TAG, "onStart projection: " + info);
                    onProjectionStart();
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    if (DEBUG) Log.d(TAG, "onStop projection: " + info);
                    onProjectionEnd();
                }
            };

    public SensitiveContentProtectionManagerService(@NonNull Context context) {
        super(context);
        mNotificationListener = new NotificationListener();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (phase != SystemService.PHASE_BOOT_COMPLETED) {
            return;
        }

        if (DEBUG) Log.d(TAG, "onBootPhase - PHASE_BOOT_COMPLETED");

        init(getContext().getSystemService(MediaProjectionManager.class),
                LocalServices.getService(WindowManagerInternal.class));
    }

    @VisibleForTesting
    void init(MediaProjectionManager projectionManager,
            WindowManagerInternal windowManager) {
        if (DEBUG) Log.d(TAG, "init");

        checkNotNull(projectionManager, "Failed to get valid MediaProjectionManager");
        checkNotNull(windowManager, "Failed to get valid WindowManagerInternal");

        mProjectionManager = projectionManager;
        mWindowManager = windowManager;

        // TODO(b/317250444): use MediaProjectionManagerService directly, reduces unnecessary
        //  handler, delegate, and binder death recipient
        mProjectionManager.addCallback(mProjectionCallback, new Handler(Looper.getMainLooper()));

        try {
            mNotificationListener.registerAsSystemService(getContext(),
                    new ComponentName(getContext(), NotificationListener.class),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            // Intra-process call, should never happen.
        }
    }

    /** Cleanup any callbacks and listeners */
    @VisibleForTesting
    void onDestroy() {
        if (mProjectionManager != null) {
            mProjectionManager.removeCallback(mProjectionCallback);
        }

        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            // Intra-process call, should never happen.
        }

        if (mWindowManager != null) {
            onProjectionEnd();
        }
    }

    private void onProjectionStart() {
        StatusBarNotification[] notifications;
        try {
            notifications = mNotificationListener.getActiveNotifications();
        } catch (SecurityException e) {
            Log.e(TAG, "SensitiveContentProtectionManagerService doesn't have access.", e);
            notifications = new StatusBarNotification[0];
        }

        RankingMap rankingMap;
        try {
            rankingMap = mNotificationListener.getCurrentRanking();
        } catch (SecurityException e) {
            Log.e(TAG, "SensitiveContentProtectionManagerService doesn't have access.", e);
            rankingMap = null;
        }

        // notify windowmanager of any currently posted sensitive content notifications
        Set<PackageInfo> packageInfos = getSensitivePackagesFromNotifications(
                notifications,
                rankingMap);

        mWindowManager.setShouldBlockScreenCaptureForApp(packageInfos);
    }

    private void onProjectionEnd() {
        // notify windowmanager to clear any sensitive notifications observed during projection
        // session
        mWindowManager.setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    private Set<PackageInfo> getSensitivePackagesFromNotifications(
            StatusBarNotification[] notifications, RankingMap rankingMap) {
        if (rankingMap == null) {
            Log.w(TAG, "Ranking map not initialized.");
            return Collections.emptySet();
        }

        Set<PackageInfo> sensitivePackages = new ArraySet<>();
        for (StatusBarNotification sbn : notifications) {
            NotificationListenerService.Ranking ranking =
                    rankingMap.getRawRankingObject(sbn.getKey());
            if (ranking != null && ranking.hasSensitiveContent()) {
                PackageInfo info = new PackageInfo(sbn.getPackageName(), sbn.getUid());
                sensitivePackages.add(info);
            }
        }
        return sensitivePackages;
    }

    // TODO(b/317251408): add trigger that updates on onNotificationPosted,
    //  onNotificationRankingUpdate and onListenerConnected
    @VisibleForTesting
    static class NotificationListener extends NotificationListenerService {}
}
