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

import static android.provider.Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS;

import static com.android.server.notification.Flags.screenshareNotificationHiding;

import android.annotation.MainThread;
import android.app.IActivityManager;
import android.content.Context;
import android.database.ExecutorContentObserver;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Trace;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.Assert;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.settings.GlobalSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Implementation of SensitiveNotificationProtectionController. **/
@SysUISingleton
public class SensitiveNotificationProtectionControllerImpl
        implements SensitiveNotificationProtectionController {
    private static final String LOG_TAG = "SNPC";
    private final SensitiveNotificationProtectionControllerLogger mLogger;
    private final ArraySet<String> mExemptPackages = new ArraySet<>();
    private final ListenerSet<Runnable> mListeners = new ListenerSet<>();
    private volatile MediaProjectionInfo mProjection;
    boolean mDisableScreenShareProtections = false;

    @VisibleForTesting
    final MediaProjectionManager.Callback mMediaProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    Trace.beginSection("SNPC.onProjectionStart");
                    try {
                        if (mDisableScreenShareProtections) {
                            Log.w(LOG_TAG,
                                    "Screen share protections disabled, ignoring projectionstart");
                            mLogger.logProjectionStart(false, info.getPackageName());
                            return;
                        }

                        // Only enable sensitive content protection if sharing full screen
                        // Launch cookie only set (non-null) if sharing single app/task
                        updateProjectionStateAndNotifyListeners(
                                (info.getLaunchCookie() == null) ? info : null);
                        mLogger.logProjectionStart(isSensitiveStateActive(), info.getPackageName());
                    } finally {
                        Trace.endSection();
                    }
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    Trace.beginSection("SNPC.onProjectionStop");
                    try {
                        mLogger.logProjectionStop();
                        updateProjectionStateAndNotifyListeners(null);
                    } finally {
                        Trace.endSection();
                    }
                }
            };

    @Inject
    public SensitiveNotificationProtectionControllerImpl(
            Context context,
            GlobalSettings settings,
            MediaProjectionManager mediaProjectionManager,
            IActivityManager activityManager,
            @Main Handler mainHandler,
            @Background Executor bgExecutor,
            SensitiveNotificationProtectionControllerLogger logger) {
        mLogger = logger;

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
        settings.registerContentObserver(
                DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS,
                developerOptionsObserver);

        // Get current setting value
        bgExecutor.execute(() -> developerOptionsObserver.onChange(true));

        bgExecutor.execute(() -> {
            ArraySet<String> exemptPackages = new ArraySet<>();
            // Exempt SystemUI
            exemptPackages.add(context.getPackageName());

            // Exempt approved bug report handlers
            try {
                exemptPackages.addAll(activityManager.getBugreportWhitelistedPackages());
            } catch (RemoteException e) {
                Log.e(
                        LOG_TAG,
                        "Error adding bug report handlers to exemption, continuing without",
                        e);
                // silent failure, skip adding packages to exemption
            }

            // if currently projecting, notify listeners of exemption changes
            mainHandler.post(() -> {
                Trace.beginSection("SNPC.exemptPackagesUpdated");
                try {
                    updateExemptPackagesAndNotifyListeners(exemptPackages);
                } finally {
                    Trace.endSection();
                }
            });
        });

        mediaProjectionManager.addCallback(mMediaProjectionCallback, mainHandler);
    }

    /**
     * Notify listeners of possible ProjectionState change regardless of current
     * isSensitiveStateActive value. Method used to ensure updates occur after mExemptPackages gets
     * updated, which directly changes the outcome of isSensitiveStateActive
     */
    @MainThread
    private void updateExemptPackagesAndNotifyListeners(ArraySet<String> exemptPackages) {
        Assert.isMainThread();
        mExemptPackages.addAll(exemptPackages);

        if (mProjection != null) {
            mListeners.forEach(Runnable::run);
        }
    }

    /**
     * Update ProjectionState respecting current isSensitiveStateActive value. Only notifies
     * listeners
     */
    @MainThread
    private void updateProjectionStateAndNotifyListeners(MediaProjectionInfo info) {
        Assert.isMainThread();
        // capture previous state
        boolean wasSensitive = isSensitiveStateActive();

        // update internal state
        mProjection = info;

        // if either previous or new state is sensitive, notify listeners.
        if (wasSensitive || isSensitiveStateActive()) {
            mListeners.forEach(Runnable::run);
        }
    }

    @Override
    public void registerSensitiveStateListener(Runnable onSensitiveStateChanged) {
        mListeners.addIfAbsent(onSensitiveStateChanged);
    }

    @Override
    public void unregisterSensitiveStateListener(Runnable onSensitiveStateChanged) {
        mListeners.remove(onSensitiveStateChanged);
    }

    // TODO(b/323396693): opportunity for optimization
    @Override
    public boolean isSensitiveStateActive() {
        MediaProjectionInfo projection = mProjection;
        if (projection == null) {
            return false;
        }

        return !mExemptPackages.contains(projection.getPackageName());
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

        // Only protect/redact notifications if the developer has not explicitly set notification
        // visibility as public and users has not adjusted default channel visibility to private
        boolean notificationRequestsRedaction = entry.isNotificationVisibilityPrivate();
        boolean userForcesRedaction = entry.isChannelVisibilityPrivate();
        return notificationRequestsRedaction || userForcesRedaction;
    }
}
