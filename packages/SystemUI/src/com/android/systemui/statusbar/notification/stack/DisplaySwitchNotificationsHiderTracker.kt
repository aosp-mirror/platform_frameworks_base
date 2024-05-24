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

package com.android.systemui.statusbar.notification.stack

import com.android.internal.util.LatencyTracker
import com.android.internal.util.LatencyTracker.ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE
import com.android.internal.util.LatencyTracker.ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Tracks latencies related to temporary hiding notifications while measuring
 * them, which is an optimization to show some content as early as possible
 * and perform notifications measurement later.
 * See [HideNotificationsInteractor].
 */
class DisplaySwitchNotificationsHiderTracker @Inject constructor(
    private val notificationsInteractor: ShadeInteractor,
    private val latencyTracker: LatencyTracker
) {

    suspend fun trackNotificationHideTime(shouldHideNotifications: Flow<Boolean>) {
        shouldHideNotifications
            .collect { shouldHide ->
                if (shouldHide) {
                    latencyTracker.onActionStart(ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE)
                } else {
                    latencyTracker.onActionEnd(ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE)
                }
            }
    }

    suspend fun trackNotificationHideTimeWhenVisible(shouldHideNotifications: Flow<Boolean>) {
        combine(shouldHideNotifications, notificationsInteractor.isAnyExpanded)
            { hidden, shadeExpanded -> hidden && shadeExpanded }
            .distinctUntilChanged()
            .collect { hiddenButShouldBeVisible ->
                if (hiddenButShouldBeVisible) {
                    latencyTracker.onActionStart(
                            ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN)
                } else {
                    latencyTracker.onActionEnd(
                            ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN)
                }
            }
    }
}