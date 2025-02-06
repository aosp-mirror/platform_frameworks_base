/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import com.android.app.tracing.FlowTracing.traceEach
import com.android.app.tracing.TraceUtils.traceAsyncClosable
import com.android.app.tracing.TrackGroupUtils.trackGroup
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tracks notification rebindings in progress as a result of a configuration change (such as density
 * or font size)
 */
@SysUISingleton
class NotificationRebindingTracker
@Inject
constructor(
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    @Background private val bgScope: CoroutineScope,
    @Application private val appScope: CoroutineScope,
) : CoreStartable {

    private val rebindingKeys = MutableStateFlow(emptySet<String>())
    private val activeKeys: Flow<Set<String>> =
        activeNotificationsInteractor.allRepresentativeNotifications
            .map { notifications: Map<String, *> ->
                notifications.map { (notifKey, _) -> notifKey }.toSet()
            }
            .traceEach(trackGroup("shade", "activeKeys"))

    /**
     * Emits the current number of active notification rebinding in progress.
     *
     * Note the usaged of the [appScope] instead of the bg one is intentional, as we need the value
     * immediately also in the same frame if it changes.
     */
    val rebindingInProgressCount: StateFlow<Int> =
        rebindingKeys
            .map { it.size }
            .traceEach(trackGroup("shade", "rebindingInProgressCount"), traceEmissionCount = true)
            .stateIn(appScope, started = SharingStarted.Eagerly, initialValue = 0)

    override fun start() {
        syncRebindingKeysWithActiveKeys()
    }

    private fun syncRebindingKeysWithActiveKeys() {
        // Let's make sure that the "rebindingKeys" set doesn't contain entries that are not active
        // anymore.
        bgScope.launch {
            activeKeys.collect { activeKeys ->
                rebindingKeys.update { currentlyBeingInflated ->
                    currentlyBeingInflated.intersect(activeKeys)
                }
            }
        }
    }

    /** Should be called when the inflation begins */
    fun trackRebinding(key: String): RebindFinishedCallback {
        val endTrace =
            traceAsyncClosable(
                trackGroupName = "Notifications",
                trackName = "Rebinding",
                sliceName = "Rebinding in progress for $key",
            )
        rebindingKeys.value += key
        return RebindFinishedCallback {
            endTrace()
            rebindingKeys.value -= key
        }
    }

    /**
     * Callback to notify the end of a rebiding. Views are expected to be in the hierarchy when this
     * is called.
     */
    fun interface RebindFinishedCallback {
        fun onFinished()
    }
}
