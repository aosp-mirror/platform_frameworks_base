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

import android.view.View
import android.widget.ImageView
import androidx.annotation.IdRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardIndicationArea
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow

class BottomAreaSection
@Inject
constructor(
    private val viewModel: KeyguardQuickAffordancesCombinedViewModel,
    private val falsingManager: FalsingManager,
    private val vibratorHelper: VibratorHelper,
    private val indicationController: KeyguardIndicationController,
    private val indicationAreaViewModel: KeyguardIndicationAreaViewModel,
) {
    /**
     * Renders a single lockscreen shortcut.
     *
     * @param isStart Whether the shortcut goes on the left (in left-to-right locales).
     * @param applyPadding Whether to apply padding around the shortcut, this is needed if the
     *   shortcut is placed along the edges of the display.
     */
    @Composable
    fun SceneScope.Shortcut(
        isStart: Boolean,
        applyPadding: Boolean,
        modifier: Modifier = Modifier,
    ) {
        MovableElement(
            key = if (isStart) StartButtonElementKey else EndButtonElementKey,
            modifier = modifier,
        ) {
            content {
                Shortcut(
                    viewId = if (isStart) R.id.start_button else R.id.end_button,
                    viewModel = if (isStart) viewModel.startButton else viewModel.endButton,
                    transitionAlpha = viewModel.transitionAlpha,
                    falsingManager = falsingManager,
                    vibratorHelper = vibratorHelper,
                    indicationController = indicationController,
                    modifier =
                        if (applyPadding) {
                            Modifier.shortcutPadding()
                        } else {
                            Modifier
                        }
                )
            }
        }
    }

    @Composable
    fun SceneScope.IndicationArea(
        modifier: Modifier = Modifier,
    ) {
        MovableElement(
            key = IndicationAreaElementKey,
            modifier = modifier.shortcutPadding(),
        ) {
            content {
                IndicationArea(
                    indicationAreaViewModel = indicationAreaViewModel,
                    indicationController = indicationController,
                )
            }
        }
    }

    @Composable
    fun shortcutSizeDp(): DpSize {
        return DpSize(
            width = dimensionResource(R.dimen.keyguard_affordance_fixed_width),
            height = dimensionResource(R.dimen.keyguard_affordance_fixed_height),
        )
    }

    @Composable
    private fun Shortcut(
        @IdRes viewId: Int,
        viewModel: Flow<KeyguardQuickAffordanceViewModel>,
        transitionAlpha: Flow<Float>,
        falsingManager: FalsingManager,
        vibratorHelper: VibratorHelper,
        indicationController: KeyguardIndicationController,
        modifier: Modifier = Modifier,
    ) {
        val (binding, setBinding) = mutableStateOf<KeyguardQuickAffordanceViewBinder.Binding?>(null)

        AndroidView(
            factory = { context ->
                val padding =
                    context.resources.getDimensionPixelSize(
                        R.dimen.keyguard_affordance_fixed_padding
                    )
                val view =
                    LaunchableImageView(context, null).apply {
                        id = viewId
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background =
                            ResourcesCompat.getDrawable(
                                context.resources,
                                R.drawable.keyguard_bottom_affordance_bg,
                                context.theme
                            )
                        foreground =
                            ResourcesCompat.getDrawable(
                                context.resources,
                                R.drawable.keyguard_bottom_affordance_selected_border,
                                context.theme
                            )
                        visibility = View.INVISIBLE
                        setPadding(padding, padding, padding, padding)
                    }

                setBinding(
                    KeyguardQuickAffordanceViewBinder.bind(
                        view,
                        viewModel,
                        transitionAlpha,
                        falsingManager,
                        vibratorHelper,
                    ) {
                        indicationController.showTransientIndication(it)
                    }
                )

                view
            },
            onRelease = { binding?.destroy() },
            modifier =
                modifier.size(
                    width = shortcutSizeDp().width,
                    height = shortcutSizeDp().height,
                )
        )
    }

    @Composable
    private fun IndicationArea(
        indicationAreaViewModel: KeyguardIndicationAreaViewModel,
        indicationController: KeyguardIndicationController,
        modifier: Modifier = Modifier,
    ) {
        val (disposable, setDisposable) = mutableStateOf<DisposableHandle?>(null)

        AndroidView(
            factory = { context ->
                val view = KeyguardIndicationArea(context, null)
                setDisposable(
                    KeyguardIndicationAreaBinder.bind(
                        view = view,
                        viewModel = indicationAreaViewModel,
                        indicationController = indicationController,
                    )
                )
                view
            },
            onRelease = { disposable?.dispose() },
            modifier = modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Modifier.shortcutPadding(): Modifier {
        return this.padding(
                horizontal = dimensionResource(R.dimen.keyguard_affordance_horizontal_offset)
            )
            .padding(bottom = dimensionResource(R.dimen.keyguard_affordance_vertical_offset))
    }
}

private val StartButtonElementKey = ElementKey("StartButton")
private val EndButtonElementKey = ElementKey("EndButton")
private val IndicationAreaElementKey = ElementKey("IndicationArea")
