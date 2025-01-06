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
import android.content.res.Resources
import androidx.compose.runtime.getValue
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.ui.controller.MediaHostStatesManager
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.qs.composefragment.dagger.QSFragmentComposeModule
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Named
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Indicates whether a particular UMO in [LOCATION_QQS] or [LOCATION_QS] should currently show in a
 * row with the tiles, based on its visibility and device configuration. If the player is not
 * visible, it will never indicate that media should show in row.
 */
class MediaInRowInLandscapeViewModel
@AssistedInject
constructor(
    @Main resources: Resources,
    configurationInteractor: ConfigurationInteractor,
    shadeModeInteractor: ShadeModeInteractor,
    private val mediaHostStatesManager: MediaHostStatesManager,
    @Named(QSFragmentComposeModule.QS_USING_MEDIA_PLAYER) private val usingMedia: Boolean,
    @Assisted @MediaLocation private val inLocation: Int,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("MediaInRowInLanscapeViewModel - $inLocation")

    val shouldMediaShowInRow: Boolean
        get() = usingMedia && inSingleShade && isLandscapeAndLong && isMediaVisible

    private val inSingleShade: Boolean by
        hydrator.hydratedStateOf(
            traceName = "inSingleShade",
            initialValue = shadeModeInteractor.shadeMode.value == ShadeMode.Single,
            source = shadeModeInteractor.shadeMode.map { it == ShadeMode.Single },
        )

    private val isLandscapeAndLong: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isLandscapeAndLong",
            initialValue = resources.configuration.isLandscapeAndLong,
            source = configurationInteractor.configurationValues.map { it.isLandscapeAndLong },
        )

    private val isMediaVisible by
        hydrator.hydratedStateOf(
            traceName = "isMediaVisible",
            initialValue = false,
            source =
                conflatedCallbackFlow {
                        val callback =
                            object : MediaHostStatesManager.Callback {
                                override fun onHostStateChanged(
                                    location: Int,
                                    mediaHostState: MediaHostState,
                                ) {
                                    if (location == inLocation) {
                                        trySend(mediaHostState.visible)
                                    }
                                }
                            }
                        mediaHostStatesManager.addCallback(callback)

                        awaitClose { mediaHostStatesManager.removeCallback(callback) }
                    }
                    .onStart {
                        emit(
                            mediaHostStatesManager.mediaHostStates.get(inLocation)?.visible ?: false
                        )
                    },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(@MediaLocation inLocation: Int): MediaInRowInLandscapeViewModel
    }
}

private val Configuration.isLandscapeAndLong: Boolean
    get() =
        orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (screenLayout and Configuration.SCREENLAYOUT_LONG_MASK) ==
                Configuration.SCREENLAYOUT_LONG_YES
