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
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.navigationbar.gestural.data.respository.GestureRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * {@link GestureInteractor} helps interact with gesture-related logic, including accessing the
 * underlying {@link GestureRepository}.
 */
class GestureInteractor
@Inject
constructor(
    private val gestureRepository: GestureRepository,
    @Application private val scope: CoroutineScope
) {
    enum class Scope {
        Local,
        Global
    }

    private val _localGestureBlockedActivities = MutableStateFlow<Set<ComponentName>>(setOf())
    /** A {@link StateFlow} for listening to changes in Activities where gestures are blocked */
    val gestureBlockedActivities: StateFlow<Set<ComponentName>>
        get() =
            combine(
                    gestureRepository.gestureBlockedActivities,
                    _localGestureBlockedActivities.asStateFlow()
                ) { global, local ->
                    global + local
                }
                .stateIn(scope, SharingStarted.WhileSubscribed(), setOf())

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

    /**
     * Checks whether the specified {@link Activity} {@link ComponentName} is being blocked from
     * gestures.
     */
    fun areGesturesBlocked(activity: ComponentName): Boolean {
        return gestureBlockedActivities.value.contains(activity)
    }
}
