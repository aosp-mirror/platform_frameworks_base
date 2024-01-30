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

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Flags.keyguardBottomAreaRefactor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Binds a keyguard indication area view to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object KeyguardIndicationAreaBinder {

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardIndicationAreaViewModel,
        keyguardRootViewModel: KeyguardRootViewModel,
        indicationController: KeyguardIndicationController,
    ): DisposableHandle {
        val indicationArea: ViewGroup = view.requireViewById(R.id.keyguard_indication_area)
        indicationController.setIndicationArea(indicationArea)

        val indicationText: TextView = indicationArea.requireViewById(R.id.keyguard_indication_text)
        val indicationTextBottom: TextView =
            indicationArea.requireViewById(R.id.keyguard_indication_text_bottom)

        view.clipChildren = false
        view.clipToPadding = false

        val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        if (keyguardBottomAreaRefactor()) {
                            keyguardRootViewModel.alpha.collect { alpha ->
                                indicationArea.apply {
                                    this.importantForAccessibility =
                                        if (alpha == 0f) {
                                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                        } else {
                                            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                                        }
                                    this.alpha = alpha
                                }
                            }
                        } else {
                            viewModel.alpha.collect { alpha ->
                                indicationArea.apply {
                                    this.importantForAccessibility =
                                        if (alpha == 0f) {
                                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                        } else {
                                            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                                        }
                                    this.alpha = alpha
                                }
                            }
                        }
                    }

                    launch {
                        viewModel.indicationAreaTranslationX.collect { translationX ->
                            indicationArea.translationX = translationX
                        }
                    }

                    launch {
                        combine(
                                viewModel.isIndicationAreaPadded,
                                configurationBasedDimensions.map { it.indicationAreaPaddingPx },
                            ) { isPadded, paddingIfPaddedPx ->
                                if (isPadded) {
                                    paddingIfPaddedPx
                                } else {
                                    0
                                }
                            }
                            .collect { paddingPx ->
                                indicationArea.setPadding(paddingPx, 0, paddingPx, 0)
                            }
                    }

                    launch {
                        configurationBasedDimensions
                            .map { it.defaultBurnInPreventionYOffsetPx }
                            .flatMapLatest { defaultBurnInOffsetY ->
                                viewModel.indicationAreaTranslationY(defaultBurnInOffsetY)
                            }
                            .collect { translationY -> indicationArea.translationY = translationY }
                    }

                    launch {
                        configurationBasedDimensions.collect { dimensions ->
                            indicationText.setTextSize(
                                TypedValue.COMPLEX_UNIT_PX,
                                dimensions.indicationTextSizePx.toFloat(),
                            )
                            indicationTextBottom.setTextSize(
                                TypedValue.COMPLEX_UNIT_PX,
                                dimensions.indicationTextSizePx.toFloat(),
                            )
                        }
                    }

                    launch {
                        viewModel.configurationChange.collect {
                            configurationBasedDimensions.value = loadFromResources(view)
                        }
                    }
                }
            }
        return disposableHandle
    }

    private fun loadFromResources(view: View): ConfigurationBasedDimensions {
        return ConfigurationBasedDimensions(
            defaultBurnInPreventionYOffsetPx =
                view.resources.getDimensionPixelOffset(R.dimen.default_burn_in_prevention_offset),
            indicationAreaPaddingPx =
                view.resources.getDimensionPixelOffset(R.dimen.keyguard_indication_area_padding),
            indicationTextSizePx =
                view.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.text_size_small_material,
                ),
        )
    }

    private data class ConfigurationBasedDimensions(
        val defaultBurnInPreventionYOffsetPx: Int,
        val indicationAreaPaddingPx: Int,
        val indicationTextSizePx: Int,
    )
}
