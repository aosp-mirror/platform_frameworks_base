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

package com.android.systemui.volume.dialog.ui

import android.content.Context
import android.content.res.Resources
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Provides cached resources [Flow]s that update when the configuration changes.
 *
 * Consume or use [kotlinx.coroutines.flow.first] to get the value.
 */
@VolumeDialogScope
class VolumeDialogResources
@Inject
constructor(
    @VolumeDialog private val coroutineScope: CoroutineScope,
    @UiBackground private val uiBackgroundContext: CoroutineContext,
    private val context: Context,
    private val configurationController: ConfigurationController,
) {

    val dialogShowDurationMillis: Flow<Long> = configurationResource {
        getInteger(R.integer.config_dialogShowAnimationDurationMs).toLong()
    }

    val dialogHideDurationMillis: Flow<Long> = configurationResource {
        getInteger(R.integer.config_dialogHideAnimationDurationMs).toLong()
    }

    private fun <T> configurationResource(get: Resources.() -> T): Flow<T> =
        configurationController.onConfigChanged
            .map { context.resources.get() }
            .onStart { emit(context.resources.get()) }
            .flowOn(uiBackgroundContext)
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()
}
