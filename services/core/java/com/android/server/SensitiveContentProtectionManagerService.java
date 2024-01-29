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
import android.os.Trace;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerInternal;

import java.util.Set;

/**
 * Service that monitors for notifications with sensitive content and protects content from screen
 * sharing
 */
public final class SensitiveContentProtectionManagerService extends SystemService {
    private static final String TAG = "SensitiveContentProtect";
    private static final boolean DEBUG = false;

    @VisibleForTesting NotificationListener mNotificationListener;
    private @Nullable MediaProjectionManager mProjectionManager;
    private @Nullable WindowManagerInternal mWindowManager;

    final Object mSensitiveContentProtectionLock = new Object();

    @GuardedBy("mSensitiveContentProtectionLock")
    private boolean mProjectionActive = false;

    private final MediaProjectionManager.Callback mProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    if (DEBUG) Log.d(TAG, "onStart projection: " + info);
                    Trace.beginSection(
                            "SensitiveContentProtectionManagerService.onProjectionStart");
                    try {
                        onProjectionStart();
                    } finally {
                        Trace.endSection();
                    }
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    if (DEBUG) Log.d(TAG, "onStop projection: " + info);
                    Trace.beginSection("SensitiveContentProtectionManagerService.onProjectionStop");
                    try {
                        onProjectionEnd();
                    } finally {
                        Trace.endSection();
                    }
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
    void init(MediaProjectionManager projectionManager, WindowManagerInternal windowManager) {
        if (DEBUG) Log.d(TAG, "init");

        checkNotNull(projectionManager, "Failed to get valid MediaProjectionManager");
        checkNotNull(windowManager, "Failed to get valid WindowManagerInternal");

        mProjectionManager = projectionManager;
        mWindowManager = windowManager;

        // TODO(b/317250444): use MediaProjectionManagerService directly, reduces unnecessary
        //  handler, delegate, and binder death recipient
        mProjectionManager.addCallback(mProjectionCallback, new Handler(Looper.getMainLooper()));

        try {
            mNotificationListener.registerAsSystemService(
                    getContext(),
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
        synchronized (mSensitiveContentProtectionLock) {
            mProjectionActive = true;
            updateAppsThatShouldBlockScreenCapture();
        }
    }

    private void onProjectionEnd() {
        synchronized (mSensitiveContentProtectionLock) {
            mProjectionActive = false;

            // notify windowmanager to clear any sensitive notifications observed during projection
            // session
            mWindowManager.clearBlockedApps();
        }
    }

    private void updateAppsThatShouldBlockScreenCapture() {
        RankingMap rankingMap;
        try {
            rankingMap = mNotificationListener.getCurrentRanking();
        } catch (SecurityException e) {
            Log.e(TAG, "SensitiveContentProtectionManagerService doesn't have access.", e);
            rankingMap = null;
        }

        updateAppsThatShouldBlockScreenCapture(rankingMap);
    }

    private void updateAppsThatShouldBlockScreenCapture(RankingMap rankingMap) {
        StatusBarNotification[] notifications;
        try {
            notifications = mNotificationListener.getActiveNotifications();
        } catch (SecurityException e) {
            Log.e(TAG, "SensitiveContentProtectionManagerService doesn't have access.", e);
            notifications = new StatusBarNotification[0];
        }

        // notify windowmanager of any currently posted sensitive content notifications
        ArraySet<PackageInfo> packageInfos =
                getSensitivePackagesFromNotifications(notifications, rankingMap);

        mWindowManager.addBlockScreenCaptureForApps(packageInfos);
    }

    private ArraySet<PackageInfo> getSensitivePackagesFromNotifications(
            @NonNull StatusBarNotification[] notifications, RankingMap rankingMap) {
        ArraySet<PackageInfo> sensitivePackages = new ArraySet<>();
        if (rankingMap == null) {
            Log.w(TAG, "Ranking map not initialized.");
            return sensitivePackages;
        }

        for (StatusBarNotification sbn : notifications) {
            PackageInfo info = getSensitivePackageFromNotification(sbn, rankingMap);
            if (info != null) {
                sensitivePackages.add(info);
            }
        }
        return sensitivePackages;
    }

    private PackageInfo getSensitivePackageFromNotification(
            StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) {
            Log.w(TAG, "Unable to protect null notification");
            return null;
        }
        if (rankingMap == null) {
            Log.w(TAG, "Ranking map not initialized.");
            return null;
        }

        NotificationListenerService.Ranking ranking = rankingMap.getRawRankingObject(sbn.getKey());
        if (ranking != null && ranking.hasSensitiveContent()) {
            return new PackageInfo(sbn.getPackageName(), sbn.getUid());
        }
        return null;
    }

    @VisibleForTesting
    class NotificationListener extends NotificationListenerService {
        @Override
        public void onListenerConnected() {
            super.onListenerConnected();
            Trace.beginSection("SensitiveContentProtectionManagerService.onListenerConnected");
            try {
                // Projection started before notification listener was connected
                synchronized (mSensitiveContentProtectionLock) {
                    if (mProjectionActive) {
                        updateAppsThatShouldBlockScreenCapture();
                    }
                }
            } finally {
                Trace.endSection();
            }
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            super.onNotificationPosted(sbn, rankingMap);
            Trace.beginSection("SensitiveContentProtectionManagerService.onNotificationPosted");
            try {
                synchronized (mSensitiveContentProtectionLock) {
                    if (!mProjectionActive) {
                        return;
                    }

                    // notify windowmanager of any currently posted sensitive content notifications
                    PackageInfo packageInfo = getSensitivePackageFromNotification(sbn, rankingMap);

                    if (packageInfo != null) {
                        mWindowManager.addBlockScreenCaptureForApps(
                                new ArraySet(Set.of(packageInfo)));
                    }
                }
            } finally {
                Trace.endSection();
            }
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
            super.onNotificationRankingUpdate(rankingMap);
            Trace.beginSection(
                    "SensitiveContentProtectionManagerService.onNotificationRankingUpdate");
            try {
                synchronized (mSensitiveContentProtectionLock) {
                    if (mProjectionActive) {
                        updateAppsThatShouldBlockScreenCapture(rankingMap);
                    }
                }
            } finally {
                Trace.endSection();
            }
        }
    }
}
