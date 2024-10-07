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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A repository for storing gesture related information */
interface GestureRepository {
    /** A {@link StateFlow} tracking matchers that can block gestures. */
    val gestureBlockedMatchers: StateFlow<Set<TaskMatcher>>

    /** Adds a matcher to determine whether a gesture should be blocked. */
    suspend fun addGestureBlockedMatcher(matcher: TaskMatcher)

    /** Removes a matcher from blocking from gestures. */
    suspend fun removeGestureBlockedMatcher(matcher: TaskMatcher)
}

@SysUISingleton
class GestureRepositoryImpl
@Inject
constructor(@Main private val mainDispatcher: CoroutineDispatcher) : GestureRepository {
    private val _gestureBlockedMatchers = MutableStateFlow<Set<TaskMatcher>>(emptySet())

    override val gestureBlockedMatchers: StateFlow<Set<TaskMatcher>>
        get() = _gestureBlockedMatchers

    override suspend fun addGestureBlockedMatcher(matcher: TaskMatcher) =
        withContext(mainDispatcher) {
            val existingMatchers = _gestureBlockedMatchers.value
            if (existingMatchers.contains(matcher)) {
                return@withContext
            }

            _gestureBlockedMatchers.value = existingMatchers.toMutableSet().apply { add(matcher) }
        }

    override suspend fun removeGestureBlockedMatcher(matcher: TaskMatcher) =
        withContext(mainDispatcher) {
            val existingMatchers = _gestureBlockedMatchers.value
            if (!existingMatchers.contains(matcher)) {
                return@withContext
            }

            _gestureBlockedMatchers.value =
                existingMatchers.toMutableSet().apply { remove(matcher) }
        }
}
