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
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.composable.section

import android.view.LayoutInflater
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class SettingsMenuSection
@Inject
constructor(
    private val viewModel: KeyguardSettingsMenuViewModel,
    private val touchHandlingViewModel: KeyguardTouchHandlingViewModel,
    private val vibratorHelper: VibratorHelper,
    private val activityStarter: ActivityStarter,
) {
    @Composable
    @SuppressWarnings("InflateParams") // null is passed into the inflate call, on purpose.
    fun SettingsMenu(
        onPlaced: (Rect?) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val (disposableHandle, setDisposableHandle) =
            remember { mutableStateOf<DisposableHandle?>(null) }
        AndroidView(
            factory = { context ->
                LayoutInflater.from(context)
                    .inflate(
                        R.layout.keyguard_settings_popup_menu,
                        null,
                    )
                    .apply {
                        isVisible = false
                        alpha = 0f

                        setDisposableHandle(
                            KeyguardSettingsViewBinder.bind(
                                view = this,
                                viewModel = viewModel,
                                touchHandlingViewModel = touchHandlingViewModel,
                                rootViewModel = null,
                                vibratorHelper = vibratorHelper,
                                activityStarter = activityStarter,
                            )
                        )
                    }
            },
            onRelease = { disposableHandle?.dispose() },
            modifier =
                modifier
                    .padding(
                        bottom = dimensionResource(R.dimen.keyguard_affordance_vertical_offset),
                    )
                    .padding(
                        horizontal =
                            dimensionResource(R.dimen.keyguard_affordance_horizontal_offset),
                    )
                    .onPlaced { coordinates ->
                        onPlaced(
                            if (!coordinates.size.toSize().isEmpty()) {
                                Rect(coordinates.positionInParent(), coordinates.size.toSize())
                            } else {
                                null
                            }
                        )
                    },
        )
    }
}
