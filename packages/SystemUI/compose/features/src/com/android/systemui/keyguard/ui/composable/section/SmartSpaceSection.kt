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

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.composable.modifier.onTopPlacementChanged
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject

class SmartSpaceSection
@Inject
constructor(
    private val lockscreenSmartspaceController: LockscreenSmartspaceController,
    private val keyguardUnlockAnimationController: KeyguardUnlockAnimationController,
    private val keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
) {
    @Composable
    fun SceneScope.SmartSpace(
        burnInParams: BurnInParameters,
        onTopChanged: (top: Float?) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier.onTopPlacementChanged(onTopChanged),
        ) {
            if (!keyguardSmartspaceViewModel.isSmartspaceEnabled) {
                return
            }

            val paddingBelowClockStart = dimensionResource(R.dimen.below_clock_padding_start)
            val paddingBelowClockEnd = dimensionResource(R.dimen.below_clock_padding_end)

            if (keyguardSmartspaceViewModel.isDateWeatherDecoupled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            // All items will be constrained to be as tall as the shortest item.
                            .height(IntrinsicSize.Min)
                            .padding(
                                start = paddingBelowClockStart,
                            ),
                ) {
                    Date(
                        modifier =
                            Modifier.burnInAware(
                                viewModel = aodBurnInViewModel,
                                params = burnInParams,
                            ),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Weather(
                        modifier =
                            Modifier.burnInAware(
                                viewModel = aodBurnInViewModel,
                                params = burnInParams,
                            ),
                    )
                }
            }

            Card(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = paddingBelowClockStart,
                            end = paddingBelowClockEnd,
                        )
                        .burnInAware(
                            viewModel = aodBurnInViewModel,
                            params = burnInParams,
                        ),
            )
        }
    }

    @Composable
    private fun Card(
        modifier: Modifier = Modifier,
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    addView(
                        lockscreenSmartspaceController.buildAndConnectView(this).apply {
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                )

                            keyguardUnlockAnimationController.lockscreenSmartspace = this
                        }
                    )
                }
            },
            onRelease = { keyguardUnlockAnimationController.lockscreenSmartspace = null },
            modifier = modifier,
        )
    }

    @Composable
    private fun Weather(
        modifier: Modifier = Modifier,
    ) {
        val isVisible by keyguardSmartspaceViewModel.isWeatherVisible.collectAsState()
        if (!isVisible) {
            return
        }

        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    addView(
                        lockscreenSmartspaceController.buildAndConnectWeatherView(this).apply {
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                )
                        }
                    )
                }
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun Date(
        modifier: Modifier = Modifier,
    ) {
        val isVisible by keyguardSmartspaceViewModel.isDateVisible.collectAsState()
        if (!isVisible) {
            return
        }

        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    addView(
                        lockscreenSmartspaceController.buildAndConnectDateView(this).apply {
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                )
                        }
                    )
                }
            },
            modifier = modifier,
        )
    }
}
