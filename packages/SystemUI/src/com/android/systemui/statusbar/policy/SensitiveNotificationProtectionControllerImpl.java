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

import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Trace;
import android.service.notification.StatusBarNotification;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.ListenerSet;

import javax.inject.Inject;

/** Implementation of SensitiveNotificationProtectionController. **/
@SysUISingleton
public class SensitiveNotificationProtectionControllerImpl
        implements SensitiveNotificationProtectionController {
    private final MediaProjectionManager mMediaProjectionManager;
    private final ListenerSet<Runnable> mListeners = new ListenerSet<>();
    private volatile MediaProjectionInfo mProjection;

    @VisibleForTesting
    final MediaProjectionManager.Callback mMediaProjectionCallback =
            new MediaProjectionManager.Callback() {
                @Override
                public void onStart(MediaProjectionInfo info) {
                    Trace.beginSection(
                            "SNPC.onProjectionStart");
                    // Only enable sensitive content protection if sharing full screen
                    // Launch cookie only set (non-null) if sharing single app/task
                    updateProjectionState((info.getLaunchCookie() == null) ? info : null);
                    Trace.endSection();
                }

                @Override
                public void onStop(MediaProjectionInfo info) {
                    Trace.beginSection(
                            "SNPC.onProjectionStop");
                    updateProjectionState(null);
                    Trace.endSection();
                }

                private void updateProjectionState(MediaProjectionInfo info) {
                    // capture previous state
                    boolean wasSensitive = isSensitiveStateActive();

                    // update internal state
                    mProjection = info;

                    // if either previous or new state is sensitive, notify listeners.
                    if (wasSensitive || isSensitiveStateActive()) {
                        mListeners.forEach(Runnable::run);
                    }
                }
            };

    @Inject
    public SensitiveNotificationProtectionControllerImpl(
            MediaProjectionManager mediaProjectionManager,
            @Main Handler mainHandler) {
        mMediaProjectionManager = mediaProjectionManager;

        if (screenshareNotificationHiding()) {
            mMediaProjectionManager.addCallback(mMediaProjectionCallback, mainHandler);
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

    @Override
    public boolean isSensitiveStateActive() {
        // TODO(b/316955558): Add disabled by developer option
        // TODO(b/316955306): Add feature exemption for sysui and bug handlers
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
        if (sbn.getNotification().isFgsOrUij()) {
            return !sbn.getPackageName().equals(projection.getPackageName());
        }

        return true;
    }
}
