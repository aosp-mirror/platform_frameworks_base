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

import static android.permission.flags.Flags.sensitiveContentImprovements;
import static android.permission.flags.Flags.sensitiveContentMetricsBugfix;
import static android.permission.flags.Flags.sensitiveNotificationAppProtection;
import static android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS;
import static android.view.flags.Flags.sensitiveContentAppProtection;

import static com.android.internal.util.FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION;
import static com.android.internal.util.FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__SOURCE__FRAMEWORKS;
import static com.android.internal.util.FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__STATE__START;
import static com.android.internal.util.FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__STATE__STOP;
import static com.android.internal.util.FrameworkStatsLog.SENSITIVE_NOTIFICATION_APP_PROTECTION_SESSION;
import static com.android.server.wm.WindowManagerInternal.OnWindowRemovedListener;

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
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerInternal;

import java.util.Objects;
import java.util.Random;
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
    @Nullable
    private MediaProjectionManager mProjectionManager;

    @GuardedBy("mSensitiveContentProtectionLock")
    @Nullable
    private MediaProjectionSession mMediaProjectionSession;

    private PackageManagerInternal mPackageManagerInternal;

    @Nullable
    private WindowManagerInternal mWindowManager;

    // screen recorder packages exempted from screen share protection.
    private ArraySet<String> mExemptedPackages = null;

    final Object mSensitiveContentProtectionLock = new Object();

    private final ArraySet<PackageInfo> mPackagesShowingSensitiveContent = new ArraySet<>();

    @GuardedBy("mSensitiveContentProtectionLock")
    private boolean mProjectionActive = false;

    private static class MediaProjectionSession {
        private final int mUid; // UID of app that started projection session
        private final long mSessionId;
        private final boolean mIsExempted;
        private final ArraySet<String> mAllSeenNotificationKeys = new ArraySet<>();
        private final ArraySet<String> mSeenOtpNotificationKeys = new ArraySet<>();

        MediaProjectionSession(int uid, boolean isExempted, long sessionId) {
            mUid = uid;
            mIsExempted = isExempted;
            mSessionId = sessionId;
        }

        public void logProjectionSessionStart() {
            FrameworkStatsLog.write(
                    SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION,
                    mSessionId,
                    mUid,
                    mIsExempted,
                    SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__STATE__START,
                    SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__SOURCE__FRAMEWORKS
            );
        }

        public void logProjectionSessionStop() {
            FrameworkStatsLog.write(
                    SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION,
                    mSessionId,
                    mUid,
                    mIsExempted,
                    SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__STATE__STOP,
                    SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__SOURCE__FRAMEWORKS
            );
        }

        public void logAppNotificationsProtected() {
            FrameworkStatsLog.write(
                    SENSITIVE_NOTIFICATION_APP_PROTECTION_SESSION,
                    mSessionId,
                    mAllSeenNotificationKeys.size(),
                    mSeenOtpNotificationKeys.size());
        }

        public void logAppBlocked(int uid) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.SENSITIVE_CONTENT_APP_PROTECTION,
                    mSessionId,
                    uid,
                    mUid,
                    FrameworkStatsLog.SENSITIVE_CONTENT_APP_PROTECTION__STATE__BLOCKED
            );
        }

        public void logAppUnblocked(int uid) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.SENSITIVE_CONTENT_APP_PROTECTION,
                    mSessionId,
                    uid,
                    mUid,
                    FrameworkStatsLog.SENSITIVE_CONTENT_APP_PROTECTION__STATE__UNBLOCKED
            );
        }

        private void addSeenNotificationKey(String key) {
            mAllSeenNotificationKeys.add(key);
        }

        private void addSeenOtpNotificationKey(String key) {
            mAllSeenNotificationKeys.add(key);
            mSeenOtpNotificationKeys.add(key);
        }

        public void addSeenNotifications(
                @NonNull StatusBarNotification[] notifications,
                @NonNull RankingMap rankingMap) {
            for (StatusBarNotification sbn : notifications) {
                if (sbn == null) {
                    Log.w(TAG, "Unable to parse null notification");
                    continue;
                }

                if (notificationHasSensitiveContent(sbn, rankingMap)) {
                    addSeenOtpNotificationKey(sbn.getKey());
                } else {
                    addSeenNotificationKey(sbn.getKey());
                }
            }
        }
    }

    private final MediaProjectionManager.Callback mProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    if (DEBUG) Log.d(TAG, "onStart projection: " + info);
                    Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                            "SensitiveContentProtectionManagerService.onProjectionStart");
                    try {
                        onProjectionStart(info);
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                    }
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    if (DEBUG) Log.d(TAG, "onStop projection: " + info);
                    Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                            "SensitiveContentProtectionManagerService.onProjectionStop");
                    try {
                        onProjectionEnd();
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                    }
                }
            };

    private final OnWindowRemovedListener mOnWindowRemovedListener = token -> {
        synchronized (mSensitiveContentProtectionLock) {
            mPackagesShowingSensitiveContent.removeIf(pkgInfo -> pkgInfo.getWindowToken() == token);
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
                LocalServices.getService(PackageManagerInternal.class),
                getExemptedPackages()
        );
        if (sensitiveContentAppProtection()) {
            publishBinderService(Context.SENSITIVE_CONTENT_PROTECTION_SERVICE,
                    new SensitiveContentProtectionManagerServiceBinder());
        }
    }

    @VisibleForTesting
    void init(MediaProjectionManager projectionManager, WindowManagerInternal windowManager,
            PackageManagerInternal packageManagerInternal, ArraySet<String> exemptedPackages) {
        if (DEBUG) Log.d(TAG, "init");

        Objects.requireNonNull(projectionManager);
        Objects.requireNonNull(windowManager);

        mProjectionManager = projectionManager;
        mWindowManager = windowManager;
        mPackageManagerInternal = packageManagerInternal;
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

        if (sensitiveContentAppProtection()) {
            mWindowManager.registerOnWindowRemovedListener(mOnWindowRemovedListener);
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

    private void onProjectionStart(MediaProjectionInfo projectionInfo) {
        boolean isPackageExempted = (mExemptedPackages != null && mExemptedPackages.contains(
                projectionInfo.getPackageName()))
                || canRecordSensitiveContent(projectionInfo.getPackageName())
                || isAutofillServiceRecorderPackage(projectionInfo.getUserHandle().getIdentifier(),
                        projectionInfo.getPackageName());
        // TODO(b/324447419): move GlobalSettings lookup to background thread
        boolean isFeatureDisabled = Settings.Global.getInt(getContext().getContentResolver(),
                DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS, 0) != 0;
        int uid = mPackageManagerInternal.getPackageUid(projectionInfo.getPackageName(), 0,
                projectionInfo.getUserHandle().getIdentifier());
        synchronized (mSensitiveContentProtectionLock) {
            mMediaProjectionSession = new MediaProjectionSession(
                    uid, isPackageExempted || isFeatureDisabled, new Random().nextLong());
            mMediaProjectionSession.logProjectionSessionStart();

            if (isPackageExempted || isFeatureDisabled) {
                Log.w(TAG, "projection session is exempted, package ="
                        + projectionInfo.getPackageName() + ", isFeatureDisabled="
                        + isFeatureDisabled);
                return;
            }

            mProjectionActive = true;

            if (sensitiveContentMetricsBugfix()) {
                mWindowManager.setBlockScreenCaptureForAppsSessionId(
                        mMediaProjectionSession.mSessionId);
            }

            if (sensitiveNotificationAppProtection()) {
                updateAppsThatShouldBlockScreenCapture();
            }

            if (sensitiveContentAppProtection() && mPackagesShowingSensitiveContent.size() > 0) {
                mWindowManager.addBlockScreenCaptureForApps(mPackagesShowingSensitiveContent);
            }
        }
    }

    private void onProjectionEnd() {
        synchronized (mSensitiveContentProtectionLock) {
            mProjectionActive = false;
            if (mMediaProjectionSession != null) {
                mMediaProjectionSession.logProjectionSessionStop();
                if (sensitiveContentImprovements()) {
                    mMediaProjectionSession.logAppNotificationsProtected();
                }
                mMediaProjectionSession = null;
            }

            // notify windowmanager to clear any sensitive notifications observed during projection
            // session
            mWindowManager.clearBlockedApps();
        }
    }

    @GuardedBy("mSensitiveContentProtectionLock")
    private void updateAppsThatShouldBlockScreenCapture() {
        RankingMap rankingMap;
        try {
            rankingMap = mNotificationListener.getCurrentRanking();
        } catch (SecurityException e) {
            Log.e(TAG, "SensitiveContentProtectionManagerService doesn't have access.", e);
            rankingMap = null;
        }

        if (rankingMap == null) {
            Log.w(TAG, "Ranking map not initialized.");
            return;
        }

        updateAppsThatShouldBlockScreenCapture(rankingMap);
    }

    @GuardedBy("mSensitiveContentProtectionLock")
    private void updateAppsThatShouldBlockScreenCapture(@NonNull RankingMap rankingMap) {
        StatusBarNotification[] notifications;
        try {
            notifications = mNotificationListener.getActiveNotifications();
        } catch (SecurityException e) {
            Log.e(TAG, "SensitiveContentProtectionManagerService doesn't have access.", e);
            notifications = new StatusBarNotification[0];
        }

        if (sensitiveContentImprovements() && mMediaProjectionSession != null) {
            mMediaProjectionSession.addSeenNotifications(notifications, rankingMap);
        }

        // notify windowmanager of any currently posted sensitive content notifications
        ArraySet<PackageInfo> packageInfos =
                getSensitivePackagesFromNotifications(notifications, rankingMap);

        if (packageInfos.size() > 0) {
            mWindowManager.addBlockScreenCaptureForApps(packageInfos);
        }
    }

    private static @NonNull ArraySet<PackageInfo> getSensitivePackagesFromNotifications(
            @NonNull StatusBarNotification[] notifications, @NonNull RankingMap rankingMap) {
        ArraySet<PackageInfo> sensitivePackages = new ArraySet<>();
        for (StatusBarNotification sbn : notifications) {
            if (sbn == null) {
                Log.w(TAG, "Unable to parse null notification");
                continue;
            }

            PackageInfo info = getSensitivePackageFromNotification(sbn, rankingMap);
            if (info != null) {
                sensitivePackages.add(info);
            }
        }
        return sensitivePackages;
    }

    private static @Nullable PackageInfo getSensitivePackageFromNotification(
            @NonNull StatusBarNotification sbn, @NonNull RankingMap rankingMap) {
        if (notificationHasSensitiveContent(sbn, rankingMap)) {
            return new PackageInfo(sbn.getPackageName(), sbn.getUid());
        }
        return null;
    }

    private static boolean notificationHasSensitiveContent(
            @NonNull StatusBarNotification sbn, @NonNull RankingMap rankingMap) {
        NotificationListenerService.Ranking ranking = rankingMap.getRawRankingObject(sbn.getKey());
        return ranking != null && ranking.hasSensitiveContent();
    }

    @VisibleForTesting
    class NotificationListener extends NotificationListenerService {
        @Override
        public void onListenerConnected() {
            super.onListenerConnected();
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                    "SensitiveContentProtectionManagerService.onListenerConnected");
            try {
                // Projection started before notification listener was connected
                synchronized (mSensitiveContentProtectionLock) {
                    if (mProjectionActive) {
                        updateAppsThatShouldBlockScreenCapture();
                    }
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            super.onNotificationPosted(sbn, rankingMap);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                    "SensitiveContentProtectionManagerService.onNotificationPosted");
            try {
                if (sbn == null) {
                    Log.w(TAG, "Unable to parse null notification");
                    return;
                }

                if (rankingMap == null) {
                    Log.w(TAG, "Ranking map not initialized.");
                    return;
                }

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

                    if (sensitiveContentImprovements() && mMediaProjectionSession != null) {
                        if (packageInfo != null) {
                            mMediaProjectionSession.addSeenOtpNotificationKey(sbn.getKey());
                        } else {
                            mMediaProjectionSession.addSeenNotificationKey(sbn.getKey());
                        }
                    }
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
            super.onNotificationRankingUpdate(rankingMap);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                    "SensitiveContentProtectionManagerService.onNotificationRankingUpdate");
            try {
                if (rankingMap == null) {
                    Log.w(TAG, "Ranking map not initialized.");
                    return;
                }

                synchronized (mSensitiveContentProtectionLock) {
                    if (mProjectionActive) {
                        updateAppsThatShouldBlockScreenCapture(rankingMap);
                    }
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }
    }

    /**
     * Block projection for a package window when the window is showing sensitive content on
     * the screen, the projection is unblocked when window no more shows sensitive content.
     *
     * @param windowToken               window where the content is shown.
     * @param packageName               package name.
     * @param uid                       uid of the package.
     * @param isShowingSensitiveContent whether the window is showing sensitive content.
     */
    @VisibleForTesting
    void setSensitiveContentProtection(IBinder windowToken, String packageName, int uid,
            boolean isShowingSensitiveContent) {
        synchronized (mSensitiveContentProtectionLock) {
            // The window token distinguish this package from packages added for notifications.
            PackageInfo packageInfo = new PackageInfo(packageName, uid, windowToken);
            // track these packages to protect when screen share starts.
            if (isShowingSensitiveContent) {
                mPackagesShowingSensitiveContent.add(packageInfo);
                if (mPackagesShowingSensitiveContent.size() > 100) {
                    Log.w(TAG, "Unexpectedly large number of sensitive windows, count: "
                            + mPackagesShowingSensitiveContent.size());
                }
            } else {
                mPackagesShowingSensitiveContent.remove(packageInfo);
            }
            if (!mProjectionActive) {
                return;
            }

            if (DEBUG) {
                Log.d(TAG, "setSensitiveContentProtection - current package=" + packageInfo
                        + ", isShowingSensitiveContent=" + isShowingSensitiveContent
                        + ", sensitive packages=" + mPackagesShowingSensitiveContent);
            }

            ArraySet<PackageInfo> packageInfos = new ArraySet<>();
            packageInfos.add(packageInfo);
            if (isShowingSensitiveContent) {
                mWindowManager.addBlockScreenCaptureForApps(packageInfos);
                if (mMediaProjectionSession != null) {
                    mMediaProjectionSession.logAppBlocked(uid);
                }
            } else {
                mWindowManager.removeBlockScreenCaptureForApps(packageInfos);
                if (mMediaProjectionSession != null) {
                    mMediaProjectionSession.logAppUnblocked(uid);
                }
            }
        }
    }

    // TODO: b/328251279 - Autofill service exemption is temporary and will be removed in future.
    private boolean isAutofillServiceRecorderPackage(int userId, String projectionPackage) {
        String autofillServiceName = Settings.Secure.getStringForUser(
                getContext().getContentResolver(), Settings.Secure.AUTOFILL_SERVICE, userId);
        if (DEBUG) {
            Log.d(TAG, "autofill service for user " + userId + " is " + autofillServiceName);
        }

        if (autofillServiceName == null) {
            return false;
        }
        ComponentName serviceComponent = ComponentName.unflattenFromString(autofillServiceName);
        if (serviceComponent == null) {
            return false;
        }
        String autofillServicePackage = serviceComponent.getPackageName();

        return autofillServicePackage != null
                && autofillServicePackage.equals(projectionPackage);
    }

    private final class SensitiveContentProtectionManagerServiceBinder
            extends ISensitiveContentProtectionManager.Stub {
        public void setSensitiveContentProtection(IBinder windowToken, String packageName,
                boolean isShowingSensitiveContent) {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                    "SensitiveContentProtectionManagerService.setSensitiveContentProtection");
            try {
                int callingUid = Binder.getCallingUid();
                verifyCallingPackage(callingUid, packageName);
                final long identity = Binder.clearCallingIdentity();
                try {
                    if (isShowingSensitiveContent
                            && mWindowManager.getWindowName(windowToken) == null) {
                        Log.e(TAG, "window token is not know to WMS, can't apply protection,"
                                + " token: " + windowToken + ", package: " + packageName);
                        return;
                    }
                    SensitiveContentProtectionManagerService.this.setSensitiveContentProtection(
                            windowToken, packageName, callingUid, isShowingSensitiveContent);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
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
