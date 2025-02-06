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

package com.android.systemui.statusbar.notification.promoted

import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.Coordinator
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import javax.inject.Inject

/** A coordinator that may automatically promote certain notifications. */
interface AutomaticPromotionCoordinator : Coordinator {
    companion object {
        /**
         * An extra that should be set on notifications that were automatically promoted. Used in
         * case we want to disable certain features for only automatically promoted notifications
         * (but not normally promoted notifications).
         */
        const val EXTRA_WAS_AUTOMATICALLY_PROMOTED = "android.wasAutomaticallyPromoted"

        /**
         * An extra set only on automatically promoted notifications that contains text that could
         * reasonably be the short critical text. For now, we're only extracting arrival times. Will
         * be set as a String.
         */
        const val EXTRA_AUTOMATICALLY_EXTRACTED_SHORT_CRITICAL_TEXT =
            "android.automaticallyExtractedShortCriticalText"
    }
}

/** A default implementation of [AutomaticPromotionCoordinator] that doesn't promote anything. */
@CoordinatorScope
class EmptyAutomaticPromotionCoordinator @Inject constructor() : AutomaticPromotionCoordinator {
    override fun attach(pipeline: NotifPipeline) {}
}
