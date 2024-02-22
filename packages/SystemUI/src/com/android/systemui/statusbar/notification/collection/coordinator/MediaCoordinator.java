/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.android.systemui.media.controls.domain.pipeline.MediaDataManagerKt.isMediaNotification;

import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Flags;
import com.android.systemui.media.controls.util.MediaFeatureFlag;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.icon.IconManager;

import javax.inject.Inject;

/**
 * Coordinates hiding (filtering) of media notifications.
 */
@CoordinatorScope
public class MediaCoordinator implements Coordinator {
    private static final String TAG = "MediaCoordinator";

    private final Boolean mIsMediaFeatureEnabled;
    private final IStatusBarService mStatusBarService;
    private final IconManager mIconManager;

    private static final int STATE_ICONS_UNINFLATED = 0;
    private static final int STATE_ICONS_INFLATED = 1;
    private static final int STATE_ICONS_ERROR = 2;

    private final ArrayMap<NotificationEntry, Integer> mIconsState = new ArrayMap<>();

    private final NotifFilter mMediaFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(@NonNull NotificationEntry entry, long now) {
            if (!mIsMediaFeatureEnabled || !isMediaNotification(entry.getSbn())) {
                return false;
            }

            if (!Flags.notificationsBackgroundMediaIcons()) {
                inflateOrUpdateIcons(entry);
            }

            return true;
        }
    };

    private final NotifCollectionListener mCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryInit(@NonNull NotificationEntry entry) {
            // We default to STATE_ICONS_UNINFLATED anyway, so there's no need to initialize it.
            if (!Flags.notificationsBackgroundMediaIcons()) {
                mIconsState.put(entry, STATE_ICONS_UNINFLATED);
            }
        }

        @Override
        public void onEntryAdded(@NonNull NotificationEntry entry) {
            if (Flags.notificationsBackgroundMediaIcons()) {
                if (isMediaNotification(entry.getSbn())) {
                    inflateOrUpdateIcons(entry);
                }
            }
        }

        @Override
        public void onEntryUpdated(@NonNull NotificationEntry entry) {
            if (mIconsState.getOrDefault(entry, STATE_ICONS_UNINFLATED) == STATE_ICONS_ERROR) {
                // The update may have fixed the inflation error, so give it another chance.
                mIconsState.put(entry, STATE_ICONS_UNINFLATED);
            }

            if (Flags.notificationsBackgroundMediaIcons()) {
                if (isMediaNotification(entry.getSbn())) {
                    inflateOrUpdateIcons(entry);
                }
            }
        }

        @Override
        public void onEntryCleanUp(@NonNull NotificationEntry entry) {
            mIconsState.remove(entry);
        }
    };

    private void inflateOrUpdateIcons(NotificationEntry entry) {
        switch (mIconsState.getOrDefault(entry, STATE_ICONS_UNINFLATED)) {
            case STATE_ICONS_UNINFLATED:
                try {
                    mIconManager.createIcons(entry);
                    mIconsState.put(entry, STATE_ICONS_INFLATED);
                } catch (InflationException e) {
                    reportInflationError(entry, e);
                    mIconsState.put(entry, STATE_ICONS_ERROR);
                }
                break;
            case STATE_ICONS_INFLATED:
                try {
                    mIconManager.updateIcons(entry);
                } catch (InflationException e) {
                    reportInflationError(entry, e);
                    mIconsState.put(entry, STATE_ICONS_ERROR);
                }
                break;
            case STATE_ICONS_ERROR:
                // do nothing
                break;
        }
    }

    private void reportInflationError(NotificationEntry entry, Exception e) {
        // This is the same logic as in PreparationCoordinator; it doesn't handle media
        // notifications when the media feature is enabled since they aren't displayed in the shade,
        // so we have to handle inflating the icons (for AOD, at the very least) and reporting any
        // errors ourselves.
        try {
            final StatusBarNotification sbn = entry.getSbn();
            // report notification inflation errors back up
            // to notification delegates
            mStatusBarService.onNotificationError(
                    sbn.getPackageName(),
                    sbn.getTag(),
                    sbn.getId(),
                    sbn.getUid(),
                    sbn.getInitialPid(),
                    e.getMessage(),
                    sbn.getUser().getIdentifier());
        } catch (RemoteException ex) {
            // System server is dead, nothing to do about that
        }
    }

    @Inject
    public MediaCoordinator(MediaFeatureFlag featureFlag, IStatusBarService statusBarService,
            IconManager iconManager) {
        mIsMediaFeatureEnabled = featureFlag.getEnabled();
        mStatusBarService = statusBarService;
        mIconManager = iconManager;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addPreGroupFilter(mMediaFilter);
        pipeline.addCollectionListener(mCollectionListener);
    }
}
