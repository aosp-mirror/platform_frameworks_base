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
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.flow.map

/**
 * Provides the number of [rows] to use with a paginated grid, by tracking the resource
 * [R.integer.quick_settings_max_rows].
 */
@SysUISingleton
class PaginatedGridRepository
@Inject
constructor(
    @Main private val resources: Resources,
    configurationRepository: ConfigurationRepository,
) {
    val rows =
        configurationRepository.onConfigurationChange.emitOnStart().map {
            resources.getInteger(R.integer.quick_settings_max_rows)
        }
}
