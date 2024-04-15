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

package com.android.systemui.communal.util

import android.content.Context
import android.graphics.Color
import com.android.settingslib.Utils
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Wrapper around colors used for the communal UI. */
interface CommunalColors {
    /** The background color of the glanceable hub. */
    val backgroundColor: StateFlow<Color>
}

@SysUISingleton
class CommunalColorsImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val context: Context,
    configurationInteractor: ConfigurationInteractor,
) : CommunalColors {
    override val backgroundColor: StateFlow<Color> =
        configurationInteractor.onAnyConfigurationChange
            .map { loadBackgroundColor() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = loadBackgroundColor()
            )

    private fun loadBackgroundColor(): Color =
        Color.valueOf(
            Utils.getColorAttrDefaultColor(
                context,
                com.android.internal.R.attr.materialColorOutlineVariant
            )
        )
}
