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

package com.android.systemui.navigationbar.gestural.domain

import android.content.ComponentName
import com.android.app.tracing.coroutines.flow.flowOn
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.navigationbar.gestural.data.respository.GestureRepository
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.util.kotlin.combine
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * {@link GestureInteractor} helps interact with gesture-related logic, including accessing the
 * underlying {@link GestureRepository}.
 */
class GestureInteractor
@Inject
constructor(
    private val gestureRepository: GestureRepository,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    @Application private val scope: CoroutineScope,
    private val activityManagerWrapper: ActivityManagerWrapper,
    private val taskStackChangeListeners: TaskStackChangeListeners,
) {
    enum class Scope {
        Local,
        Global
    }

    private val _localGestureBlockedActivities = MutableStateFlow<Set<ComponentName>>(setOf())

    private val _topActivity =
        conflatedCallbackFlow {
                val taskListener =
                    object : TaskStackChangeListener {
                        override fun onTaskStackChanged() {
                            trySend(Unit)
                        }
                    }

                taskStackChangeListeners.registerTaskStackListener(taskListener)
                awaitClose { taskStackChangeListeners.unregisterTaskStackListener(taskListener) }
            }
            .flowOn(mainDispatcher)
            .emitOnStart()
            .mapLatest { getTopActivity() }
            .distinctUntilChanged()

    private suspend fun getTopActivity(): ComponentName? =
        withContext(backgroundCoroutineContext) {
            val runningTask = activityManagerWrapper.runningTask
            runningTask?.topActivity
        }

    val topActivityBlocked =
        combine(
            _topActivity,
            gestureRepository.gestureBlockedActivities,
            _localGestureBlockedActivities.asStateFlow()
        ) { activity, global, local ->
            activity != null && (global + local).contains(activity)
        }

    /**
     * Adds an {@link Activity} to be blocked based on component when the topmost, focused {@link
     * Activity}.
     */
    fun addGestureBlockedActivity(activity: ComponentName, gestureScope: Scope) {
        scope.launch {
            when (gestureScope) {
                Scope.Local -> {
                    _localGestureBlockedActivities.emit(
                        _localGestureBlockedActivities.value.toMutableSet().apply { add(activity) }
                    )
                }
                Scope.Global -> {
                    gestureRepository.addGestureBlockedActivity(activity)
                }
            }
        }
    }

    /** Removes an {@link Activity} from being blocked from gestures. */
    fun removeGestureBlockedActivity(activity: ComponentName, gestureScope: Scope) {
        scope.launch {
            when (gestureScope) {
                Scope.Local -> {
                    _localGestureBlockedActivities.emit(
                        _localGestureBlockedActivities.value.toMutableSet().apply {
                            remove(activity)
                        }
                    )
                }
                Scope.Global -> {
                    gestureRepository.removeGestureBlockedActivity(activity)
                }
            }
        }
    }
}
