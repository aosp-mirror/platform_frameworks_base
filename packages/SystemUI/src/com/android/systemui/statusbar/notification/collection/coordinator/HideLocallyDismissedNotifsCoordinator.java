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

import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.NOT_DISMISSED;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import javax.inject.Inject;

/**
 * Filters out notifications that have been dismissed locally (by the user) but that system server
 * hasn't yet confirmed the removal of.
 */
@CoordinatorScope
public class HideLocallyDismissedNotifsCoordinator implements Coordinator {

    @Inject
    HideLocallyDismissedNotifsCoordinator() { }

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addPreGroupFilter(mFilter);
    }

    private final NotifFilter mFilter = new NotifFilter("HideLocallyDismissedNotifsFilter") {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return entry.getDismissState() != NOT_DISMISSED;
        }
    };
}
