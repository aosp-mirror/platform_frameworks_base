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

package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Pluggable for participating in notif filtering.
 * See {@link NotifPipeline#addPreGroupFilter} and {@link NotifPipeline#addFinalizeFilter}.
 */
public abstract class NotifFilter extends Pluggable<NotifFilter> {
    protected NotifFilter(String name) {
        super(name);
    }

    /**
     * If returns true, this notification will not be included in the final list displayed to the
     * user. Filtering is performed on each active notification every time the pipeline is run.
     * This doesn't necessarily mean that your filter will get called on every notification,
     * however. If another filter returns true before yours, we'll skip straight to the next notif.
     *
     * @param entry The entry in question.
     *              If this filter is registered via {@link NotifPipeline#addPreGroupFilter},
     *              this entry will not have any grouping nor sorting information.
     *              If this filter is registered via {@link NotifPipeline#addFinalizeFilter},
     *              this entry will have grouping and sorting information.
     * @param now A timestamp in SystemClock.uptimeMillis that represents "now" for the purposes of
     *            pipeline execution. This value will be the same for all pluggable calls made
     *            during this pipeline run, giving pluggables a stable concept of "now" to compare
     *            various entries against.
     * @return True if the notif should be removed from the list
     */
    public abstract boolean shouldFilterOut(@NonNull NotificationEntry entry, long now);
}
