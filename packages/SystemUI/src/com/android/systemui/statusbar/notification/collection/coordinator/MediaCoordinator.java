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

import static com.android.systemui.media.MediaDataManagerKt.isMediaNotification;

import com.android.systemui.media.MediaFeatureFlag;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import javax.inject.Inject;

/**
 * Coordinates hiding (filtering) of media notifications.
 */
public class MediaCoordinator implements Coordinator {
    private static final String TAG = "MediaCoordinator";

    private final Boolean mIsMediaFeatureEnabled;

    private final NotifFilter mMediaFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return mIsMediaFeatureEnabled && isMediaNotification(entry.getSbn());
        }
    };

    @Inject
    public MediaCoordinator(MediaFeatureFlag featureFlag) {
        mIsMediaFeatureEnabled = featureFlag.getEnabled();
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addFinalizeFilter(mMediaFilter);
    }
}
