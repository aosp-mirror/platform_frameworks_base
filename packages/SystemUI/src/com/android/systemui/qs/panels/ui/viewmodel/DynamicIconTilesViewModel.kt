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

package com.android.systemui.qs.panels.ui.viewmodel

import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.panels.domain.interactor.DynamicIconTilesInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** View model to resize QS tiles down to icons when removed from the current tiles. */
class DynamicIconTilesViewModel
@AssistedInject
constructor(
    interactorFactory: DynamicIconTilesInteractor.Factory,
    iconTilesViewModel: IconTilesViewModel,
) : IconTilesViewModel by iconTilesViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("DynamicIconTilesViewModel")
    private val interactor = interactorFactory.create()

    val largeTilesSpanState =
        hydrator.hydratedStateOf(
            traceName = "largeTilesSpan",
            source = iconTilesViewModel.largeTilesSpan,
        )

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }
            launch { interactor.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): DynamicIconTilesViewModel
    }
}
