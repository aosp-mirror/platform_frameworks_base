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

package com.android.systemui.notifications.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Models UI state used to render the content of the notifications shade overlay.
 *
 * Different from [NotificationsShadeOverlayActionsViewModel], which only models user actions that
 * can be performed to navigate to other scenes.
 */
class NotificationsShadeOverlayContentViewModel
@AssistedInject
constructor(
    val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
    val sceneInteractor: SceneInteractor,
    private val shadeInteractor: ShadeInteractor,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("NotificationsShadeOverlayContentViewModel.hydrator")

    val showHeader: Boolean by
        hydrator.hydratedStateOf(
            traceName = "showHeader",
            initialValue =
                shouldShowHeader(
                    isShadeLayoutWide = shadeInteractor.isShadeLayoutWide.value,
                    areAnyNotificationsPresent =
                        activeNotificationsInteractor.areAnyNotificationsPresentValue,
                ),
            source =
                combine(
                    shadeInteractor.isShadeLayoutWide,
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    this::shouldShowHeader,
                ),
        )

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }

            launch {
                sceneInteractor.currentScene.collect { currentScene ->
                    when (currentScene) {
                        // TODO(b/369513770): The ShadeSession should be preserved in this scenario.
                        Scenes.Bouncer ->
                            shadeInteractor.collapseNotificationsShade(
                                loggingReason = "bouncer shown while shade is open"
                            )
                    }
                }
            }

            launch {
                shadeInteractor.isShadeTouchable
                    .distinctUntilChanged()
                    .filter { !it }
                    .collect {
                        shadeInteractor.collapseNotificationsShade(
                            loggingReason = "device became non-interactive"
                        )
                    }
            }
        }
        awaitCancellation()
    }

    fun onScrimClicked() {
        shadeInteractor.collapseNotificationsShade(loggingReason = "shade scrim clicked")
    }

    private fun shouldShowHeader(
        isShadeLayoutWide: Boolean,
        areAnyNotificationsPresent: Boolean,
    ): Boolean {
        return !isShadeLayoutWide && areAnyNotificationsPresent
    }

    @AssistedFactory
    interface Factory {
        fun create(): NotificationsShadeOverlayContentViewModel
    }
}
