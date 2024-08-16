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

package com.android.systemui.navigationbar.gestural.data.respository

import android.content.ComponentName
import android.util.ArraySet
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A repository for storing gesture related information */
interface GestureRepository {
    /** A {@link StateFlow} tracking activities currently blocked from gestures. */
    val gestureBlockedActivities: StateFlow<Set<ComponentName>>

    /** Adds an activity to be blocked from gestures. */
    suspend fun addGestureBlockedActivity(activity: ComponentName)

    /** Removes an activity from being blocked from gestures. */
    suspend fun removeGestureBlockedActivity(activity: ComponentName)
}

@SysUISingleton
class GestureRepositoryImpl
@Inject
constructor(@Main private val mainDispatcher: CoroutineDispatcher) : GestureRepository {
    private val _gestureBlockedActivities = MutableStateFlow<Set<ComponentName>>(ArraySet())

    override val gestureBlockedActivities: StateFlow<Set<ComponentName>>
        get() = _gestureBlockedActivities

    override suspend fun addGestureBlockedActivity(activity: ComponentName) =
        withContext(mainDispatcher) {
            _gestureBlockedActivities.emit(
                _gestureBlockedActivities.value.toMutableSet().apply { add(activity) }
            )
        }

    override suspend fun removeGestureBlockedActivity(activity: ComponentName) =
        withContext(mainDispatcher) {
            _gestureBlockedActivities.emit(
                _gestureBlockedActivities.value.toMutableSet().apply { remove(activity) }
            )
        }
}
