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

import static com.android.server.notification.Flags.screenshareNotificationHiding;

import android.annotation.MainThread;
import android.app.IActivityManager;
import android.content.Context;
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

import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Implementation of SensitiveNotificationProtectionController. **/
@SysUISingleton
public class SensitiveNotificationProtectionControllerImpl
        implements SensitiveNotificationProtectionController {
    private static final String LOG_TAG = "SNPC";
    private final ArraySet<String> mExemptPackages = new ArraySet<>();
    private final ListenerSet<Runnable> mListeners = new ListenerSet<>();
    private volatile MediaProjectionInfo mProjection;

    @VisibleForTesting
    final MediaProjectionManager.Callback mMediaProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    Trace.beginSection("SNPC.onProjectionStart");
                    try {
                        // Only enable sensitive content protection if sharing full screen
                        // Launch cookie only set (non-null) if sharing single app/task
                        updateProjectionStateAndNotifyListeners(
                                (info.getLaunchCookie() == null) ? info : null);
                    } finally {
                        Trace.endSection();
                    }
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    Trace.beginSection("SNPC.onProjectionStop");
                    try {
                        updateProjectionStateAndNotifyListeners(null);
                    } finally {
                        Trace.endSection();
                    }
                }
            };

    @Inject
    public SensitiveNotificationProtectionControllerImpl(
            Context context,
            MediaProjectionManager mediaProjectionManager,
            IActivityManager activityManager,
            @Main Handler mainHandler,
            @Background Executor bgExecutor) {
        if (!screenshareNotificationHiding()) {
            return;
        }

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

        // TODO(b/316955558): Add disabled by developer option

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
        if (sbn.getNotification().isFgsOrUij()) {
            return !sbn.getPackageName().equals(projection.getPackageName());
        }

        return true;
    }
}
