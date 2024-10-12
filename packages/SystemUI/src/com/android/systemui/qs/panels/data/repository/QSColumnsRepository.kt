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

package com.android.systemui.qs.panels.data.repository

import android.content.res.Resources
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class QSColumnsRepository
@Inject
constructor(
    @Application scope: CoroutineScope,
    @Main private val resources: Resources,
    configurationRepository: ConfigurationRepository,
) {
    val columns: StateFlow<Int> =
        if (DualShade.isEnabled) {
                flowOf(resources.getInteger(R.integer.quick_settings_dual_shade_num_columns))
            } else {
                configurationRepository.onConfigurationChange.emitOnStart().mapLatest {
                    resources.getInteger(R.integer.quick_settings_infinite_grid_num_columns)
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                resources.getInteger(R.integer.quick_settings_infinite_grid_num_columns),
            )
}
