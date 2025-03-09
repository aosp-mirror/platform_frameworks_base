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

import android.content.res.Configuration
import android.content.res.mainResources
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.media.controls.ui.controller.mediaHostStatesManager
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeModeInteractor

val Kosmos.mediaInRowInLandscapeViewModelFactory by
    Kosmos.Fixture {
        object : MediaInRowInLandscapeViewModel.Factory {
            override fun create(inLocation: Int): MediaInRowInLandscapeViewModel {
                return MediaInRowInLandscapeViewModel(
                    mainResources,
                    configurationInteractor,
                    shadeModeInteractor,
                    mediaHostStatesManager,
                    usingMediaInComposeFragment,
                    inLocation,
                )
            }
        }
    }

fun Kosmos.setConfigurationForMediaInRow(mediaInRow: Boolean) {
    shadeRepository.setShadeLayoutWide(!mediaInRow) // media in row only in non wide
    val config =
        Configuration(mainResources.configuration).apply {
            orientation =
                if (mediaInRow) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            screenLayout = Configuration.SCREENLAYOUT_LONG_YES
        }
    mainResources.configuration.updateFrom(config)
    fakeConfigurationRepository.onConfigurationChange(config)
    runCurrent()
}
