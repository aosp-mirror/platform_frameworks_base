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

package com.android.systemui.qs.ui.viewmodel

import androidx.lifecycle.LifecycleOwner
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the quick settings scene. */
@SysUISingleton
class QuickSettingsSceneViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    val brightnessMirrorViewModel: BrightnessMirrorViewModel,
    val shadeHeaderViewModel: ShadeHeaderViewModel,
    val qsSceneAdapter: QSSceneAdapter,
    val notifications: NotificationsPlaceholderViewModel,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    sceneBackInteractor: SceneBackInteractor,
    val mediaCarouselInteractor: MediaCarouselInteractor,
) {
    private val backScene: StateFlow<SceneKey> =
        sceneBackInteractor.backScene
            .filter { it != Scenes.QuickSettings }
            .map { it ?: Scenes.Shade }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = Scenes.Shade,
            )

    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        combine(
                qsSceneAdapter.isCustomizerShowing,
                backScene,
                transform = ::destinationScenes,
            )
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    destinationScenes(
                        isCustomizing = qsSceneAdapter.isCustomizerShowing.value,
                        backScene = backScene.value,
                    ),
            )

    val isMediaVisible: StateFlow<Boolean> = mediaCarouselInteractor.hasAnyMediaOrRecommendation

    private fun destinationScenes(
        isCustomizing: Boolean,
        backScene: SceneKey?,
    ): Map<UserAction, UserActionResult> {
        return buildMap {
            if (isCustomizing) {
                // TODO(b/332749288) Empty map so there are no back handlers and back can close
                // customizer

                // TODO(b/330200163) Add an Up from Bottom to be able to collapse the shade
                // while customizing
            } else {
                put(Back, UserActionResult(backScene ?: Scenes.Shade))
                put(Swipe(SwipeDirection.Up), UserActionResult(backScene ?: Scenes.Shade))
                put(
                    Swipe(fromSource = Edge.Bottom, direction = SwipeDirection.Up),
                    UserActionResult(SceneFamilies.Home),
                )
            }
        }
    }

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }
}
