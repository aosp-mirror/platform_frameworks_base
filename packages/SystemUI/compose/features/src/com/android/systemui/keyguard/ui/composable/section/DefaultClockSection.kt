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

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.modifiers.padding
import com.android.systemui.customization.R as customizationR
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.largeClockElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.smallClockElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.smartspaceElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.largeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.smallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockTransition.defaultClockTransitions
import com.android.systemui.keyguard.ui.composable.blueprint.rememberBurnIn
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.composable.modifier.onTopPlacementChanged
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.res.R
import javax.inject.Inject

/** Provides small clock and large clock composables for the default clock face. */
class DefaultClockSection
@Inject
constructor(
    private val viewModel: KeyguardClockViewModel,
    private val clockInteractor: KeyguardClockInteractor,
    private val aodBurnInViewModel: AodBurnInViewModel,
    private val lockscreenContentViewModel: LockscreenContentViewModel,
    private val smartSpaceSection: SmartSpaceSection,
) {
    @Composable
    fun DefaultClockLayout(
        modifier: Modifier = Modifier,
    ) {
        val isLargeClockVisible by viewModel.isLargeClockVisible.collectAsState()
        val burnIn = rememberBurnIn(clockInteractor)
        val currentScene =
            if (isLargeClockVisible) {
                largeClockScene
            } else {
                smallClockScene
            }

        LaunchedEffect(isLargeClockVisible) {
            if (isLargeClockVisible) {
                burnIn.onSmallClockTopChanged(null)
            }
        }

        SceneTransitionLayout(
            modifier = modifier,
            currentScene = currentScene,
            onChangeScene = {},
            transitions = defaultClockTransitions,
        ) {
            scene(smallClockScene) {
                Column {
                    SmallClock(
                        burnInParams = burnIn.parameters,
                        onTopChanged = burnIn.onSmallClockTopChanged,
                        modifier = Modifier.element(smallClockElementKey).fillMaxWidth()
                    )
                    SmartSpaceContent()
                }
            }

            scene(largeClockScene) {
                Column {
                    SmartSpaceContent()
                    LargeClock(modifier = Modifier.element(largeClockElementKey).fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun SceneScope.SmartSpaceContent(
        modifier: Modifier = Modifier,
    ) {
        val burnIn = rememberBurnIn(clockInteractor)
        val resources = LocalContext.current.resources

        MovableElement(key = smartspaceElementKey, modifier = modifier) {
            content {
                with(smartSpaceSection) {
                    this@SmartSpaceContent.SmartSpace(
                        burnInParams = burnIn.parameters,
                        onTopChanged = burnIn.onSmartspaceTopChanged,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(
                                    top = {
                                        lockscreenContentViewModel.getSmartSpacePaddingTop(
                                            resources
                                        )
                                    },
                                    bottom = {
                                        resources.getDimensionPixelSize(
                                            R.dimen.keyguard_status_view_bottom_margin
                                        )
                                    }
                                ),
                    )
                }
            }
        }
    }

    @Composable
    private fun SceneScope.SmallClock(
        burnInParams: BurnInParameters,
        onTopChanged: (top: Float?) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val currentClock by viewModel.currentClock.collectAsState()
        if (currentClock?.smallClock?.view == null) {
            return
        }
        viewModel.clock = currentClock

        val context = LocalContext.current

        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val newClockView = checkNotNull(currentClock).smallClock.view
                    (newClockView.parent as? ViewGroup)?.removeView(newClockView)
                    addView(newClockView)
                }
            },
            update = {
                val newClockView = checkNotNull(currentClock).smallClock.view
                it.removeAllViews()
                (newClockView.parent as? ViewGroup)?.removeView(newClockView)
                it.addView(newClockView)
            },
            modifier =
                modifier
                    .padding(
                        horizontal = dimensionResource(customizationR.dimen.clock_padding_start)
                    )
                    .padding(top = { viewModel.getSmallClockTopMargin(context) })
                    .onTopPlacementChanged(onTopChanged)
                    .burnInAware(
                        viewModel = aodBurnInViewModel,
                        params = burnInParams,
                    ),
        )
    }

    @Composable
    private fun SceneScope.LargeClock(modifier: Modifier = Modifier) {
        val currentClock by viewModel.currentClock.collectAsState()
        viewModel.clock = currentClock
        if (currentClock?.largeClock?.view == null) {
            return
        }

        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val newClockView = checkNotNull(currentClock).largeClock.view
                    (newClockView.parent as? ViewGroup)?.removeView(newClockView)
                    addView(newClockView)
                }
            },
            update = {
                val newClockView = checkNotNull(currentClock).largeClock.view
                it.removeAllViews()
                (newClockView.parent as? ViewGroup)?.removeView(newClockView)
                it.addView(newClockView)
            },
            modifier = modifier.fillMaxSize()
        )
    }
}
