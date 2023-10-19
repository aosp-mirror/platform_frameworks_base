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
 *
 */

package com.android.systemui.keyguard.ui.binder

import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.R
import com.android.systemui.keyguard.ui.viewmodel.KeyguardAmbientIndicationViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object KeyguardAmbientIndicationAreaViewBinder {
    /**
     * Defines interface for an object that acts as the binding between the view and its view-model.
     *
     * Users of the [KeyguardBottomAreaViewBinder] class should use this to control the binder after
     * it is bound.
     */
    interface Binding {
        /**
         * Returns a collection of [ViewPropertyAnimator] instances that can be used to animate the
         * indication areas.
         */
        fun getIndicationAreaAnimators(): List<ViewPropertyAnimator>

        /** Notifies that device configuration has changed. */
        fun onConfigurationChanged()

        /** Destroys this binding, releases resources, and cancels any coroutines. */
        fun destroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardAmbientIndicationViewModel,
        keyguardRootViewModel: KeyguardRootViewModel,
    ): Binding {
        val ambientIndicationArea: View? = view.findViewById(R.id.ambient_indication_container)
        val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))

        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        keyguardRootViewModel.alpha.collect { alpha ->
                            ambientIndicationArea?.apply {
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

                    launch {
                        viewModel.indicationAreaTranslationX.collect { translationX ->
                            ambientIndicationArea?.translationX = translationX
                        }
                    }

                    launch {
                        configurationBasedDimensions
                            .map { it.defaultBurnInPreventionYOffsetPx }
                            .flatMapLatest { defaultBurnInOffsetY ->
                                viewModel.indicationAreaTranslationY(defaultBurnInOffsetY)
                            }
                            .collect { translationY ->
                                ambientIndicationArea?.translationY = translationY
                            }
                    }

                }
            }


        return object : Binding {
            override fun getIndicationAreaAnimators(): List<ViewPropertyAnimator> {
                return listOf(ambientIndicationArea).mapNotNull { it?.animate() }
            }

            override fun onConfigurationChanged() {
                configurationBasedDimensions.value = loadFromResources(view)
            }

            override fun destroy() {
                disposableHandle.dispose()
            }
        }
    }

    private fun loadFromResources(view: View): ConfigurationBasedDimensions {
        return ConfigurationBasedDimensions(
            defaultBurnInPreventionYOffsetPx =
                view.resources.getDimensionPixelOffset(R.dimen.default_burn_in_prevention_offset),
        )
    }

    private data class ConfigurationBasedDimensions(
        val defaultBurnInPreventionYOffsetPx: Int,
    )
}