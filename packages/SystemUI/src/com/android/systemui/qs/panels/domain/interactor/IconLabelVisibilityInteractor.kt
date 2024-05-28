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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.qs.panels.data.repository.IconLabelVisibilityRepository
import com.android.systemui.qs.panels.shared.model.IconLabelVisibilityLog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class IconLabelVisibilityInteractor
@Inject
constructor(
    private val repo: IconLabelVisibilityRepository,
    @IconLabelVisibilityLog private val logBuffer: LogBuffer,
    @Application scope: CoroutineScope,
) {
    val showLabels: StateFlow<Boolean> =
        repo.showLabels
            .onEach { logChange(it) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), repo.showLabels.value)

    fun setShowLabels(showLabels: Boolean) {
        repo.setShowLabels(showLabels)
    }

    private fun logChange(showLabels: Boolean) {
        logBuffer.log(
            LOG_BUFFER_ICON_TILE_LABEL_VISIBILITY_CHANGE_TAG,
            LogLevel.DEBUG,
            { bool1 = showLabels },
            { "Icon tile label visibility changed: $bool1" }
        )
    }

    private companion object {
        const val LOG_BUFFER_ICON_TILE_LABEL_VISIBILITY_CHANGE_TAG = "IconLabelVisibilityChange"
    }
}
