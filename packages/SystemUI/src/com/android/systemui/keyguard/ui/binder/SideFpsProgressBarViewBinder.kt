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

package com.android.systemui.keyguard.ui.binder

import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.SideFpsController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.view.SideFpsProgressBar
import com.android.systemui.keyguard.ui.viewmodel.SideFpsProgressBarViewModel
import com.android.systemui.util.kotlin.Quint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class SideFpsProgressBarViewBinder
@Inject
constructor(
    private val viewModel: SideFpsProgressBarViewModel,
    private val view: SideFpsProgressBar,
    @Application private val applicationScope: CoroutineScope,
    private val sfpsController: dagger.Lazy<SideFpsController>,
) : CoreStartable {

    override fun start() {
        applicationScope.launch {
            viewModel.isProlongedTouchRequiredForAuthentication.collectLatest { enabled ->
                if (enabled) {
                    launch {
                        combine(
                                viewModel.isVisible,
                                viewModel.sensorLocation,
                                viewModel.shouldRotate90Degrees,
                                viewModel.isFingerprintAuthRunning,
                                viewModel.sensorWidth,
                                ::Quint
                            )
                            .collectLatest {
                                (visible, location, shouldRotate, fpDetectRunning, sensorWidth) ->
                                view.updateView(visible, location, shouldRotate, sensorWidth)
                                // We have to hide the SFPS indicator as the progress bar will
                                // be shown at the same location
                                if (visible) {
                                    sfpsController.get().hideIndicator()
                                } else if (fpDetectRunning) {
                                    sfpsController.get().showIndicator()
                                }
                            }
                    }
                    launch { viewModel.progress.collectLatest { view.setProgress(it) } }
                } else {
                    view.hideOverlay()
                }
            }
        }
    }
}
