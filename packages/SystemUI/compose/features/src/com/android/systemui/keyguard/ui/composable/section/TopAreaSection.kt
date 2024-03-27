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

package com.android.systemui.keyguard.ui.composable.section

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.modifiers.thenIf
import com.android.systemui.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.largeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.smallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeLargeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeSmallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockTransition
import com.android.systemui.keyguard.ui.composable.blueprint.rememberBurnIn
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import javax.inject.Inject

class TopAreaSection
@Inject
constructor(
    private val clockViewModel: KeyguardClockViewModel,
    private val smartSpaceSection: SmartSpaceSection,
    private val mediaCarouselSection: MediaCarouselSection,
    private val notificationSection: NotificationSection,
    private val clockSection: DefaultClockSection,
    private val clockInteractor: KeyguardClockInteractor,
) {
    @Composable
    fun DefaultClockLayoutWithNotifications(
        modifier: Modifier = Modifier,
    ) {
        val isLargeClockVisible by clockViewModel.isLargeClockVisible.collectAsState()
        val currentClockLayout by clockViewModel.currentClockLayout.collectAsState()
        val hasCustomPositionUpdatedAnimation by
            clockViewModel.hasCustomPositionUpdatedAnimation.collectAsState()

        val currentScene =
            when (currentClockLayout) {
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_LARGE_CLOCK ->
                    splitShadeLargeClockScene
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_SMALL_CLOCK ->
                    splitShadeSmallClockScene
                KeyguardClockViewModel.ClockLayout.LARGE_CLOCK -> largeClockScene
                KeyguardClockViewModel.ClockLayout.SMALL_CLOCK -> smallClockScene
            }

        val splitShadeTopMargin: Dp =
            if (Flags.centralizedStatusBarHeightFix()) {
                LargeScreenHeaderHelper.getLargeScreenHeaderHeight(LocalContext.current).dp
            } else {
                dimensionResource(id = R.dimen.large_screen_shade_header_height)
            }
        val burnIn = rememberBurnIn(clockInteractor)

        LaunchedEffect(isLargeClockVisible) {
            if (isLargeClockVisible) {
                burnIn.onSmallClockTopChanged(null)
            }
        }

        SceneTransitionLayout(
            modifier = modifier.fillMaxSize(),
            currentScene = currentScene,
            onChangeScene = {},
            transitions = ClockTransition.defaultClockTransitions,
            enableInterruptions = false,
        ) {
            scene(splitShadeLargeClockScene) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        with(smartSpaceSection) {
                            SmartSpace(
                                burnInParams = burnIn.parameters,
                                onTopChanged = burnIn.onSmartspaceTopChanged,
                            )
                        }

                        with(clockSection) {
                            LargeClock(
                                modifier =
                                    Modifier.fillMaxSize().thenIf(
                                        !hasCustomPositionUpdatedAnimation
                                    ) {
                                        // If we do not have a custom position animation, we want
                                        // the clock to be on one half of the screen.
                                        Modifier.offset {
                                            IntOffset(
                                                x =
                                                    -clockSection
                                                        .getClockCenteringDistance()
                                                        .toInt(),
                                                y = 0,
                                            )
                                        }
                                    }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Spacer(modifier = Modifier.weight(weight = 1f))
                    with(notificationSection) {
                        Notifications(
                            modifier =
                                Modifier.fillMaxHeight()
                                    .weight(weight = 1f)
                                    .padding(top = splitShadeTopMargin)
                        )
                    }
                }
            }

            scene(splitShadeSmallClockScene) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight().weight(weight = 1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        with(clockSection) {
                            SmallClock(
                                burnInParams = burnIn.parameters,
                                onTopChanged = burnIn.onSmallClockTopChanged,
                                modifier = Modifier.wrapContentSize()
                            )
                        }
                        with(smartSpaceSection) {
                            SmartSpace(
                                burnInParams = burnIn.parameters,
                                onTopChanged = burnIn.onSmartspaceTopChanged,
                            )
                        }
                        with(mediaCarouselSection) { MediaCarousel() }
                    }
                    with(notificationSection) {
                        Notifications(
                            modifier =
                                Modifier.fillMaxHeight()
                                    .weight(weight = 1f)
                                    .padding(top = splitShadeTopMargin)
                        )
                    }
                }
            }

            scene(smallClockScene) {
                Column {
                    with(clockSection) {
                        SmallClock(
                            burnInParams = burnIn.parameters,
                            onTopChanged = burnIn.onSmallClockTopChanged,
                            modifier = Modifier.wrapContentSize()
                        )
                    }
                    with(smartSpaceSection) {
                        SmartSpace(
                            burnInParams = burnIn.parameters,
                            onTopChanged = burnIn.onSmartspaceTopChanged,
                        )
                    }
                    with(mediaCarouselSection) { MediaCarousel() }
                    with(notificationSection) {
                        Notifications(modifier = Modifier.fillMaxWidth().weight(weight = 1f))
                    }
                }
            }

            scene(largeClockScene) {
                Column {
                    with(smartSpaceSection) {
                        SmartSpace(
                            burnInParams = burnIn.parameters,
                            onTopChanged = burnIn.onSmartspaceTopChanged,
                        )
                    }
                    with(clockSection) { LargeClock(modifier = Modifier.fillMaxSize()) }
                }
            }
        }
    }
}
