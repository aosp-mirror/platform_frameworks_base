/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.permission.flags.Flags.sensitiveNotificationAppProtection;
import static android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS;

import static com.android.server.notification.Flags.screenshareNotificationHiding;
import static com.android.systemui.Flags.screenshareNotificationHidingBugFix;

import android.annotation.MainThread;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ExecutorContentObserver;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.Assert;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.settings.GlobalSettings;

import java.util.Random;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Implementation of SensitiveNotificationProtectionController. **/
@SysUISingleton
public class SensitiveNotificationProtectionControllerImpl
        implements SensitiveNotificationProtectionController {
    private static final String LOG_TAG = "SNPC";
    private final SensitiveNotificationProtectionControllerLogger mLogger;
    private final PackageManager mPackageManager;
    // Packages exempt from projection session protections (if they start a projection session)
    private final ArraySet<String> mSessionProtectionExemptPackages = new ArraySet<>();
    // Packages exempt from individual notification protections (if they post a notification)
    private final ArraySet<String> mNotificationProtectionExemptPackages = new ArraySet<>();
    private final ListenerSet<Runnable> mListeners = new ListenerSet<>();
    private volatile MediaProjectionInfo mProjection;
    private SensitiveNotificatioMediaProjectionSession mActiveMediaProjectionSession;
    boolean mDisableScreenShareProtections = false;


    private static class SensitiveNotificatioMediaProjectionSession {
        final long mSessionId;
        final int mProjectionAppUid;
        final boolean mExempt;

        SensitiveNotificatioMediaProjectionSession(
                long sessionId,
                int projectionAppUid,
                boolean exempt) {
            this.mSessionId = sessionId;
            this.mProjectionAppUid = projectionAppUid;
            this.mExempt = exempt;
        }
    }

    @VisibleForTesting
    final MediaProjectionManager.Callback mMediaProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    Trace.beginSection("SNPC.onProjectionStart");
                    try {
                        updateProjectionStateAndNotifyListeners(info);
                        mLogger.logProjectionStart(isSensitiveStateActive(), info.getPackageName());

                        int packageUid;
                        try {
                            packageUid = mPackageManager.getPackageUidAsUser(info.getPackageName(),
                                    info.getUserHandle().getIdentifier());
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.w(LOG_TAG, "Package " + info.getPackageName() + " not found");
                            packageUid = -1;
                        }
                        // TODO(b/329665707): MediaProjectionSessionIdGenerator instead of random
                        //  long
                        logSensitiveContentProtectionSessionStart(
                                new Random().nextLong(), packageUid, !isSensitiveStateActive());
                    } finally {
                        Trace.endSection();
                    }
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    Trace.beginSection("SNPC.onProjectionStop");
                    try {
                        mLogger.logProjectionStop();
                        logSensitiveContentProtectionSessionStop();
                        updateProjectionStateAndNotifyListeners(null);
                    } finally {
                        Trace.endSection();
                    }
                }
            };

    private void logSensitiveContentProtectionSessionStart(
            long sessionId, int projectionAppUid, boolean exempt) {
        mActiveMediaProjectionSession =
                new SensitiveNotificatioMediaProjectionSession(sessionId, projectionAppUid, exempt);
        logSensitiveContentProtectionSession(
                mActiveMediaProjectionSession,
                FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__STATE__START);
    }

    private void logSensitiveContentProtectionSessionStop() {
        if (mActiveMediaProjectionSession == null) {
            return;
        }
        logSensitiveContentProtectionSession(
                mActiveMediaProjectionSession,
                FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__STATE__STOP);
        mActiveMediaProjectionSession = null;
    }

    private void logSensitiveContentProtectionSession(
            SensitiveNotificatioMediaProjectionSession session, int state) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION,
                session.mSessionId,
                session.mProjectionAppUid,
                session.mExempt,
                state,
                FrameworkStatsLog.SENSITIVE_CONTENT_MEDIA_PROJECTION_SESSION__SOURCE__SYS_UI);
    }

    @Inject
    public SensitiveNotificationProtectionControllerImpl(
            Context context,
            GlobalSettings settings,
            MediaProjectionManager mediaProjectionManager,
            IActivityManager activityManager,
            PackageManager packageManager,
            TelephonyManager telephonyManager,
            @Main Handler mainHandler,
            @Background Executor bgExecutor,
            SensitiveNotificationProtectionControllerLogger logger) {
        mLogger = logger;
        mPackageManager = packageManager;

        if (!screenshareNotificationHiding()) {
            return;
        }

        ExecutorContentObserver developerOptionsObserver = new ExecutorContentObserver(bgExecutor) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                boolean disableScreenShareProtections = settings.getInt(
                        DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS,
                        0) != 0;
                mainHandler.post(() -> {
                    mDisableScreenShareProtections = disableScreenShareProtections;
                });
            }
        };
        settings.registerContentObserverSync(
                DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS,
                developerOptionsObserver);

        // Get current setting value
        bgExecutor.execute(() -> developerOptionsObserver.onChange(true));

        bgExecutor.execute(() -> {
            ArraySet<String> sessionProtectionExemptPackages =
                    getSessionProtectionExemptPackages(context, activityManager);

            ArraySet<String> notificationProtectionExemptPackages =
                    getNotificationProtectionExemptPackages(telephonyManager);

            // if currently projecting, notify listeners of exemption changes
            mainHandler.post(() -> {
                Trace.beginSection("SNPC.exemptPackagesUpdated");
                try {
                    updateExemptPackagesAndNotifyListeners(sessionProtectionExemptPackages,
                            notificationProtectionExemptPackages);
                } finally {
                    Trace.endSection();
                }
            });
        });

        mediaProjectionManager.addCallback(mMediaProjectionCallback, mainHandler);
    }

    @NonNull
    private static ArraySet<String> getSessionProtectionExemptPackages(Context context,
            IActivityManager activityManager) {
        ArraySet<String> sessionProtectionExemptPackages = new ArraySet<>();
        // Exempt SystemUI
        sessionProtectionExemptPackages.add(context.getPackageName());

        // Exempt approved bug report handlers
        try {
            sessionProtectionExemptPackages.addAll(
                    activityManager.getBugreportWhitelistedPackages());
        } catch (RemoteException e) {
            Log.w(
                    LOG_TAG,
                    "Error adding bug report handlers to exemption, continuing without",
                    e);
            // silent failure, skip adding packages to exemption
        }
        return sessionProtectionExemptPackages;
    }

    @NonNull
    private static ArraySet<String> getNotificationProtectionExemptPackages(
            TelephonyManager telephonyManager) {
        ArraySet<String> notificationProtectionExemptPackages = new ArraySet<>();

        // Get Emergency Assistance Package, all notifications from this package should not be
        // hidden/redacted.
        if (screenshareNotificationHidingBugFix()) {
            try {
                String emergencyAssistancePackageName =
                        telephonyManager.getEmergencyAssistancePackageName();
                if (emergencyAssistancePackageName != null) {
                    notificationProtectionExemptPackages.add(emergencyAssistancePackageName);
                }
            } catch (IllegalStateException e) {
                Log.w(
                        LOG_TAG,
                        "Error adding emergency assistance package to exemption",
                        e);
                // silent failure, skip adding packages to exemption
            }
        }
        return notificationProtectionExemptPackages;
    }

    /**
     * Notify listeners of possible ProjectionState change regardless of current
     * isSensitiveStateActive value. Method used to ensure updates occur after mExemptPackages gets
     * updated, which directly changes the outcome of isSensitiveStateActive
     */
    @MainThread
    private void updateExemptPackagesAndNotifyListeners(
            ArraySet<String> sessionProtectionExemptPackages,
            ArraySet<String> notificationProtectionExemptPackages) {
        Assert.isMainThread();
        mSessionProtectionExemptPackages.addAll(sessionProtectionExemptPackages);
        if (screenshareNotificationHidingBugFix()) {
            mNotificationProtectionExemptPackages.addAll(notificationProtectionExemptPackages);
        }

        if (mProjection != null) {
            updateProjectionStateAndNotifyListeners(mProjection);
        }
    }

    /**
     * Update ProjectionState respecting current isSensitiveStateActive value. Only notifies
     * listeners
     */
    @MainThread
    private void updateProjectionStateAndNotifyListeners(@Nullable MediaProjectionInfo info) {
        Assert.isMainThread();
        // capture previous state
        boolean wasSensitive = isSensitiveStateActive();

        // update internal state
        mProjection = getNonExemptProjectionInfo(info);

        // if either previous or new state is sensitive, notify listeners.
        if (wasSensitive || isSensitiveStateActive()) {
            mListeners.forEach(Runnable::run);
        }
    }

    private MediaProjectionInfo getNonExemptProjectionInfo(@Nullable MediaProjectionInfo info) {
        if (mDisableScreenShareProtections) {
            Log.w(LOG_TAG, "Screen share protections disabled");
            return null;
        } else if (info != null
                && mSessionProtectionExemptPackages.contains(info.getPackageName())) {
            Log.w(LOG_TAG, "Screen share protections exempt for package " + info.getPackageName());
            return null;
        } else if (info != null && canRecordSensitiveContent(info.getPackageName())) {
            Log.w(LOG_TAG, "Screen share protections exempt for package " + info.getPackageName()
                    + " via permission");
            return null;
        } else if (info != null && info.getLaunchCookie() != null) {
            // Only enable sensitive content protection if sharing full screen
            // Launch cookie only set (non-null) if sharing single app/task
            Log.w(LOG_TAG, "Screen share protections exempt for single app screenshare");
            return null;
        }
        return info;
    }

    private boolean canRecordSensitiveContent(@NonNull String packageName) {
        // RECORD_SENSITIVE_CONTENT is flagged api on sensitiveNotificationAppProtection
        if (sensitiveNotificationAppProtection()) {
            return mPackageManager.checkPermission(
                            android.Manifest.permission.RECORD_SENSITIVE_CONTENT, packageName)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    @Override
    public void registerSensitiveStateListener(Runnable onSensitiveStateChanged) {
        mListeners.addIfAbsent(onSensitiveStateChanged);
    }

    @Override
    public void unregisterSensitiveStateListener(Runnable onSensitiveStateChanged) {
        mListeners.remove(onSensitiveStateChanged);
    }

    @Override
    public boolean isSensitiveStateActive() {
        return mProjection != null;
    }

    @Override
    public boolean shouldProtectNotification(NotificationEntry entry) {
        if (!isSensitiveStateActive()) {
            return false;
        }

        MediaProjectionInfo projection = mProjection;
        if (projection == null) {
            return false;
        }

        // Exempt foreground service notifications from protection in effort to keep screen share
        // stop actions easily accessible
        StatusBarNotification sbn = entry.getSbn();
        if (sbn.getNotification().isFgsOrUij()
                && sbn.getPackageName().equals(projection.getPackageName())) {
            return false;
        }

        if (screenshareNotificationHidingBugFix() && UserHandle.isCore(sbn.getUid())) {
            return false; // do not hide/redact notifications from system uid
        }

        if (screenshareNotificationHidingBugFix()
                && mNotificationProtectionExemptPackages.contains(sbn.getPackageName())) {
            return false; // do not hide/redact notifications from emergency app
        }

        // Only protect/redact notifications if the developer has not explicitly set notification
        // visibility as public and users has not adjusted default channel visibility to private
        boolean notificationRequestsRedaction = entry.isNotificationVisibilityPrivate();
        boolean userForcesRedaction = entry.isChannelVisibilityPrivate();
        return notificationRequestsRedaction || userForcesRedaction;
    }
}
