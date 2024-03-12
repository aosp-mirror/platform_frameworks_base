/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.common.ui.domain.interactor

import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/** Business logic related to configuration changes. */
@SysUISingleton
class ConfigurationInteractor @Inject constructor(private val repository: ConfigurationRepository) {
    /** Given [resourceId], emit the dimension pixel size on config change */
    fun dimensionPixelSize(resourceId: Int): Flow<Int> {
        return onAnyConfigurationChange.mapLatest { repository.getDimensionPixelSize(resourceId) }
    }

    /** Given a set of [resourceId]s, emit Map<ResourceId, DimensionPixelSize> on config change */
    fun dimensionPixelSize(resourceIds: Set<Int>): Flow<Map<Int, Int>> {
        return onAnyConfigurationChange.mapLatest {
            resourceIds.associateWith { repository.getDimensionPixelSize(it) }
        }
    }

    /** Emit an event on any config change */
    val onAnyConfigurationChange: Flow<Unit> =
        repository.onAnyConfigurationChange.onStart { emit(Unit) }
}
