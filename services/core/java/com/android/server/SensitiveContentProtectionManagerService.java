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

import static android.permission.flags.Flags.sensitiveNotificationAppProtection;
import static android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS;
import static android.view.flags.Flags.sensitiveContentAppProtection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;
import android.view.ISensitiveContentProtectionManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerInternal;

import java.util.Objects;
import java.util.Set;

/**
 * This service protects sensitive content from screen sharing. The service monitors notifications
 * for sensitive content and protects from screen share. The service also protects sensitive
 * content rendered on screen during screen share.
 */
public final class SensitiveContentProtectionManagerService extends SystemService {
    private static final String TAG = "SensitiveContentProtect";
    private static final boolean DEBUG = false;

    @VisibleForTesting
    @Nullable
    NotificationListener mNotificationListener;
    @Nullable private MediaProjectionManager mProjectionManager;
    @Nullable private WindowManagerInternal mWindowManager;

    // screen recorder packages exempted from screen share protection.
    private ArraySet<String> mExemptedPackages = null;

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
                        onProjectionStart(info.getPackageName());
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
        if (sensitiveNotificationAppProtection()) {
            mNotificationListener = new NotificationListener();
        }
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
                LocalServices.getService(WindowManagerInternal.class),
                getExemptedPackages());
        if (sensitiveContentAppProtection()) {
            publishBinderService(Context.SENSITIVE_CONTENT_PROTECTION_SERVICE,
                    new SensitiveContentProtectionManagerServiceBinder());
        }
    }

    @VisibleForTesting
    void init(MediaProjectionManager projectionManager, WindowManagerInternal windowManager,
            ArraySet<String> exemptedPackages) {
        if (DEBUG) Log.d(TAG, "init");

        Objects.requireNonNull(projectionManager);
        Objects.requireNonNull(windowManager);

        mProjectionManager = projectionManager;
        mWindowManager = windowManager;
        mExemptedPackages = exemptedPackages;

        // TODO(b/317250444): use MediaProjectionManagerService directly, reduces unnecessary
        //  handler, delegate, and binder death recipient
        mProjectionManager.addCallback(mProjectionCallback, getContext().getMainThreadHandler());

        if (sensitiveNotificationAppProtection()) {
            try {
                mNotificationListener.registerAsSystemService(
                        getContext(),
                        new ComponentName(getContext(), NotificationListener.class),
                        UserHandle.USER_ALL);
            } catch (RemoteException e) {
                // Intra-process call, should never happen.
            }
        }
    }

    /** Cleanup any callbacks and listeners */
    @VisibleForTesting
    void onDestroy() {
        if (mProjectionManager != null) {
            mProjectionManager.removeCallback(mProjectionCallback);
        }
        if (sensitiveNotificationAppProtection()) {
            try {
                mNotificationListener.unregisterAsSystemService();
            } catch (RemoteException e) {
                // Intra-process call, should never happen.
            }
        }

        if (mWindowManager != null) {
            onProjectionEnd();
        }
    }

    private boolean canRecordSensitiveContent(@NonNull String packageName) {
        return getContext().getPackageManager()
                .checkPermission(android.Manifest.permission.RECORD_SENSITIVE_CONTENT,
                        packageName) == PackageManager.PERMISSION_GRANTED;
    }

    // These packages are exempted from screen share protection.
    private ArraySet<String> getExemptedPackages() {
        return SystemConfig.getInstance().getBugreportWhitelistedPackages();
    }

    private void onProjectionStart(String packageName) {
        // exempt on device screen recorder as well.
        if ((mExemptedPackages != null && mExemptedPackages.contains(packageName))
                || canRecordSensitiveContent(packageName)) {
            Log.w(TAG, packageName + " is exempted from screen share protection.");
            return;
        }
        // TODO(b/324447419): move GlobalSettings lookup to background thread
        boolean disableScreenShareProtections =
                Settings.Global.getInt(getContext().getContentResolver(),
                        DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, 0) != 0;
        if (disableScreenShareProtections) {
            Log.w(TAG, "Screen share protections disabled, ignoring projection start");
            return;
        }

        synchronized (mSensitiveContentProtectionLock) {
            mProjectionActive = true;
            if (sensitiveNotificationAppProtection()) {
                updateAppsThatShouldBlockScreenCapture();
            }
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

    /**
     * Block projection for a package window when the window is showing sensitive content on
     * the screen, the projection is unblocked when window no more shows sensitive content.
     *
     * @param windowToken window where the content is shown.
     * @param packageName package name.
     * @param uid uid of the package.
     * @param isShowingSensitiveContent whether the window is showing sensitive content.
     */
    @VisibleForTesting
    void setSensitiveContentProtection(IBinder windowToken, String packageName, int uid,
            boolean isShowingSensitiveContent) {
        synchronized (mSensitiveContentProtectionLock) {
            if (!mProjectionActive) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "setSensitiveContentProtection - windowToken=" + windowToken
                        + ", package=" + packageName + ", uid=" + uid
                        + ", isShowingSensitiveContent=" + isShowingSensitiveContent);
            }

            // The window token distinguish this package from packages added for notifications.
            PackageInfo packageInfo = new PackageInfo(packageName, uid, windowToken);
            ArraySet<PackageInfo> packageInfos = new ArraySet<>();
            packageInfos.add(packageInfo);
            if (isShowingSensitiveContent) {
                mWindowManager.addBlockScreenCaptureForApps(packageInfos);
            } else {
                mWindowManager.removeBlockScreenCaptureForApps(packageInfos);
            }
        }
    }

    private final class SensitiveContentProtectionManagerServiceBinder
            extends ISensitiveContentProtectionManager.Stub {
        private final PackageManagerInternal mPackageManagerInternal;

        SensitiveContentProtectionManagerServiceBinder() {
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        }

        public void setSensitiveContentProtection(IBinder windowToken, String packageName,
                boolean isShowingSensitiveContent) {
            Trace.beginSection(
                    "SensitiveContentProtectionManagerService.setSensitiveContentProtection");
            try {
                int callingUid = Binder.getCallingUid();
                verifyCallingPackage(callingUid, packageName);
                final long identity = Binder.clearCallingIdentity();
                try {
                    SensitiveContentProtectionManagerService.this.setSensitiveContentProtection(
                            windowToken, packageName, callingUid, isShowingSensitiveContent);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } finally {
                Trace.endSection();
            }
        }

        private void verifyCallingPackage(int callingUid, String callingPackage) {
            if (mPackageManagerInternal.getPackageUid(
                    callingPackage, 0, UserHandle.getUserId(callingUid)) != callingUid) {
                throw new SecurityException("Specified calling package [" + callingPackage
                        + "] does not match the calling uid " + callingUid);
            }
        }
    }
}
