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

import com.android.app.tracing.coroutines.flow.flowOn
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.navigationbar.gestural.data.respository.GestureRepository
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
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

    private val _localGestureBlockedMatchers = MutableStateFlow<Set<TaskMatcher>>(setOf())

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

    private suspend fun getTopActivity(): TaskInfo? =
        withContext(backgroundCoroutineContext) {
            activityManagerWrapper.runningTask?.let { TaskInfo(it.topActivity, it.activityType) }
        }

    val topActivityBlocked =
        combine(
            _topActivity,
            gestureRepository.gestureBlockedMatchers,
            _localGestureBlockedMatchers.asStateFlow()
        ) { runningTask, global, local ->
            runningTask != null && (global + local).any { it.matches(runningTask) }
        }

    /** Adds an [TaskMatcher] to decide whether gestures should be blocked. */
    fun addGestureBlockedMatcher(matcher: TaskMatcher, gestureScope: Scope) {
        scope.launch {
            when (gestureScope) {
                Scope.Local -> {
                    _localGestureBlockedMatchers.emit(
                        _localGestureBlockedMatchers.value.toMutableSet().apply { add(matcher) }
                    )
                }
                Scope.Global -> {
                    gestureRepository.addGestureBlockedMatcher(matcher)
                }
            }
        }
    }

    /** Removes a gesture from deciding whether gestures should be blocked */
    fun removeGestureBlockedMatcher(matcher: TaskMatcher, gestureScope: Scope) {
        scope.launch {
            when (gestureScope) {
                Scope.Local -> {
                    _localGestureBlockedMatchers.emit(
                        _localGestureBlockedMatchers.value.toMutableSet().apply { remove(matcher) }
                    )
                }
                Scope.Global -> {
                    gestureRepository.removeGestureBlockedMatcher(matcher)
                }
            }
        }
    }
}
