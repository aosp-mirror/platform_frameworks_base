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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.flowOf

class KeyguardMediaViewModel
@AssistedInject
constructor(
    mediaCarouselInteractor: MediaCarouselInteractor,
    keyguardInteractor: KeyguardInteractor,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("KeyguardMediaViewModel.hydrator")
    /**
     * Whether media carousel is visible on lockscreen. Media may be presented on lockscreen but
     * still hidden on certain surfaces like AOD
     */
    val isMediaVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isMediaVisible",
            source =
                keyguardInteractor.isDozing.flatMapLatestConflated { isDozing ->
                    if (isDozing) {
                        flowOf(false)
                    } else {
                        mediaCarouselInteractor.hasActiveMediaOrRecommendation
                    }
                },
            initialValue =
                !keyguardInteractor.isDozing.value &&
                    mediaCarouselInteractor.hasActiveMediaOrRecommendation.value,
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(): KeyguardMediaViewModel
    }
}
