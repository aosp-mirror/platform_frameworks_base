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

package com.android.systemui.statusbar.notification.promoted.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.statusbar.notification.promoted.domain.interactor.AODPromotedNotificationInteractor
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.util.kotlin.ActivatableFlowDumper
import com.android.systemui.util.kotlin.ActivatableFlowDumperImpl
import com.android.systemui.util.time.SystemClock
import com.android.systemui.utils.coroutines.flow.transformLatestConflated
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AODPromotedNotificationViewModel
@AssistedInject
constructor(
    interactor: AODPromotedNotificationInteractor,
    systemClock: SystemClock,
    dumpManager: DumpManager,
) :
    ExclusiveActivatable(),
    ActivatableFlowDumper by ActivatableFlowDumperImpl(
        dumpManager,
        "AODPromotedNotificationViewModel",
    ) {
    private val hydrator = Hydrator("AODPromotedNotificationViewModel.hydrator")

    val content: PromotedNotificationContentModel? by
        hydrator.hydratedStateOf(
            traceName = "content",
            initialValue = null,
            source = interactor.content,
        )

    private val audiblyAlertedIconVisibleUntil: Flow<Duration?> =
        interactor.content
            .map {
                when (it) {
                    null -> null
                    else -> it.lastAudiblyAlertedMs.milliseconds + RECENTLY_ALERTED_THRESHOLD
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("audiblyAlertedIconVisibleUntil")

    private val audiblyAlertedIconVisibleFlow: Flow<Boolean> =
        audiblyAlertedIconVisibleUntil
            .transformLatestConflated { until ->
                val now = systemClock.currentTimeMillis().milliseconds

                if (until != null && until > now) {
                    emit(true)
                    delay(until - now)
                }
                emit(false)
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("audiblyAlertedIconVisible")

    val audiblyAlertedIconVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "audiblyAlertedIconVisible",
            initialValue = false,
            source = audiblyAlertedIconVisibleFlow,
        )

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { activateFlowDumper() }
            launch { hydrator.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): AODPromotedNotificationViewModel
    }

    companion object {
        private val RECENTLY_ALERTED_THRESHOLD = 30.seconds
    }
}
