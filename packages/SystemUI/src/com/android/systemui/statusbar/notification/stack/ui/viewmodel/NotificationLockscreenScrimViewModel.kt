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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.compose.animation.scene.ElementKey
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.composable.Shade
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.util.kotlin.ActivatableFlowDumper
import com.android.systemui.util.kotlin.ActivatableFlowDumperImpl
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class NotificationLockscreenScrimViewModel
@AssistedInject
constructor(
    dumpManager: DumpManager,
    shadeInteractor: ShadeInteractor,
    private val stackAppearanceInteractor: NotificationStackAppearanceInteractor,
) :
    ActivatableFlowDumper by ActivatableFlowDumperImpl(dumpManager, "NotificationScrollViewModel"),
    ExclusiveActivatable() {

    private val hydrator = Hydrator("NotificationLockscreenScrimViewModel.hydrator")

    val shadeMode: StateFlow<ShadeMode> = shadeInteractor.shadeMode

    /** The [ElementKey] to use for the scrim. */
    val element: ElementViewModel by
        hydrator.hydratedStateOf(
            traceName = "elementKey",
            initialValue = element(shadeMode.value),
            source = shadeMode.map { element(it) },
        )

    /** Sets the alpha to apply to the NSSL for fade-in on lockscreen */
    fun setAlphaForLockscreenFadeIn(alpha: Float) {
        stackAppearanceInteractor.setAlphaForLockscreenFadeIn(alpha)
    }

    override suspend fun onActivated(): Nothing {
        coroutineScopeTraced("NotificationLockscreenScrimViewModel") {
            launch { activateFlowDumper() }
            launch { hydrator.activate() }
            awaitCancellation()
        }
    }

    private fun element(shadeMode: ShadeMode): ElementViewModel {
        return if (shadeMode == ShadeMode.Single) {
            ElementViewModel(
                key = Notifications.Elements.NotificationScrim,
                color = { SingleShadeBackground },
            )
        } else {
            ElementViewModel(
                key = Shade.Elements.BackgroundScrim,
                color = { isBouncerToLockscreen ->
                    if (isBouncerToLockscreen) {
                        SplitShadeBouncerToLockscreenBackground
                    } else {
                        SplitShadeDefaultBackground
                    }
                },
            )
        }
    }

    /** Models the UI state of the scrim. */
    data class ElementViewModel(
        /** The [ElementKey] to use with an `element` modifier. */
        val key: ElementKey,
        /** A function that returns the color to use within a `background` modifier. */
        val color: @Composable (isBouncerToLockscreen: Boolean) -> Color,
    )

    @AssistedFactory
    interface Factory {
        fun create(): NotificationLockscreenScrimViewModel
    }

    companion object {
        private val SingleShadeBackground: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface

        private val SplitShadeBouncerToLockscreenBackground: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface

        private val SplitShadeDefaultBackground: Color
            @Composable
            @ReadOnlyComposable
            get() = colorResource(R.color.shade_scrim_background_dark)
    }
}
