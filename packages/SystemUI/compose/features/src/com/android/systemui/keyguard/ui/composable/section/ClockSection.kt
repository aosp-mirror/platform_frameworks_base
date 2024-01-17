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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.padding
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.customization.R as customizationR
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.modifier.onTopPlacementChanged
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import javax.inject.Inject

class ClockSection
@Inject
constructor(
    private val viewModel: KeyguardClockViewModel,
    private val clockInteractor: KeyguardClockInteractor,
) {

    @Composable
    fun SceneScope.SmallClock(
        onTopChanged: (top: Float?) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val clockSize by viewModel.clockSize.collectAsState()
        val currentClock by viewModel.currentClock.collectAsState()
        viewModel.clock = currentClock

        if (clockSize != KeyguardClockSwitch.SMALL) {
            onTopChanged(null)
            return
        }

        if (currentClock?.smallClock?.view == null) {
            return
        }

        val view = LocalView.current

        DisposableEffect(view) {
            clockInteractor.clockEventController.registerListeners(view)

            onDispose { clockInteractor.clockEventController.unregisterListeners() }
        }

        MovableElement(
            key = ClockElementKey,
            modifier = modifier,
        ) {
            content {
                AndroidView(
                    factory = { checkNotNull(currentClock).smallClock.view },
                    modifier =
                        Modifier.padding(
                                horizontal =
                                    dimensionResource(customizationR.dimen.clock_padding_start)
                            )
                            .padding(top = { viewModel.getSmallClockTopMargin(view.context) })
                            .onTopPlacementChanged(onTopChanged),
                )
            }
        }
    }

    @Composable
    fun SceneScope.LargeClock(modifier: Modifier = Modifier) {
        val clockSize by viewModel.clockSize.collectAsState()
        val currentClock by viewModel.currentClock.collectAsState()
        viewModel.clock = currentClock

        if (clockSize != KeyguardClockSwitch.LARGE) {
            return
        }

        if (currentClock?.largeClock?.view == null) {
            return
        }

        val view = LocalView.current

        DisposableEffect(view) {
            clockInteractor.clockEventController.registerListeners(view)

            onDispose { clockInteractor.clockEventController.unregisterListeners() }
        }

        MovableElement(
            key = ClockElementKey,
            modifier = modifier,
        ) {
            content {
                AndroidView(
                    factory = { checkNotNull(currentClock).largeClock.view },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private val ClockElementKey = ElementKey("Clock")
