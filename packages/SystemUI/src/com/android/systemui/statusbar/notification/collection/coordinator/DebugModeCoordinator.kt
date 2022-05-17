/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.provider.DebugModeFilterProvider
import javax.inject.Inject

/** A small coordinator which filters out notifications from non-allowed apps. */
@CoordinatorScope
class DebugModeCoordinator @Inject constructor(
    private val debugModeFilterProvider: DebugModeFilterProvider
) : Coordinator {

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addPreGroupFilter(preGroupFilter)
        debugModeFilterProvider.registerInvalidationListener(preGroupFilter::invalidateList)
    }

    private val preGroupFilter = object : NotifFilter("DebugModeCoordinator") {
        override fun shouldFilterOut(entry: NotificationEntry, now: Long) =
            debugModeFilterProvider.shouldFilterOut(entry)
    }
}