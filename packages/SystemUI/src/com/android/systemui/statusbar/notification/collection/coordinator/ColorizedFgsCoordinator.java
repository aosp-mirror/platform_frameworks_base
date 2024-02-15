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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static android.app.NotificationManager.IMPORTANCE_MIN;

import android.app.Notification;

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt;

import javax.inject.Inject;

/**
 * Handles sectioning for foreground service notifications.
 *  Puts non-min colorized foreground service notifications into the FGS section. See
 *  {@link NotifCoordinators} for section ordering priority.
 */
@CoordinatorScope
public class ColorizedFgsCoordinator implements Coordinator {
    private static final String TAG = "ColorizedCoordinator";

    @Inject
    public ColorizedFgsCoordinator() {
    }

    @Override
    public void attach(NotifPipeline pipeline) {
    }

    public NotifSectioner getSectioner() {
        return mNotifSectioner;
    }


    /**
     * Puts colorized foreground service and call notifications into its own section.
     */
    private final NotifSectioner mNotifSectioner = new NotifSectioner("ColorizedSectioner",
            NotificationPriorityBucketKt.BUCKET_FOREGROUND_SERVICE) {
        @Override
        public boolean isInSection(ListEntry entry) {
            NotificationEntry notificationEntry = entry.getRepresentativeEntry();
            if (notificationEntry != null) {
                return isColorizedForegroundService(notificationEntry) || isCall(notificationEntry);
            }
            return false;
        }

        private boolean isColorizedForegroundService(NotificationEntry entry) {
            Notification notification = entry.getSbn().getNotification();
            return notification.isForegroundService()
                    && notification.isColorized()
                    && entry.getImportance() > IMPORTANCE_MIN;
        }

        private boolean isCall(NotificationEntry entry) {
            Notification notification = entry.getSbn().getNotification();
            return entry.getImportance() > IMPORTANCE_MIN
                    && notification.isStyle(Notification.CallStyle.class);
        }
    };
}
