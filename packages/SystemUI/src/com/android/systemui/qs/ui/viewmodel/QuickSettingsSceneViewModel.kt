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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Models UI state and handles user input for the quick settings scene. */
@SysUISingleton
class QuickSettingsSceneViewModel
@Inject
constructor(
    val brightnessMirrorViewModel: BrightnessMirrorViewModel,
    val shadeHeaderViewModel: ShadeHeaderViewModel,
    val qsSceneAdapter: QSSceneAdapter,
    val notifications: NotificationsPlaceholderViewModel,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    sceneBackInteractor: SceneBackInteractor,
    val mediaCarouselInteractor: MediaCarouselInteractor,
) {
    private val backScene: Flow<SceneKey> =
        sceneBackInteractor.backScene
            .filter { it != Scenes.QuickSettings }
            .map { it ?: Scenes.Shade }

    val destinationScenes: Flow<Map<UserAction, UserActionResult>> =
        combine(
            qsSceneAdapter.isCustomizerShowing,
            backScene,
        ) { isCustomizing, backScene ->
            buildMap<UserAction, UserActionResult> {
                if (isCustomizing) {
                    // TODO(b/332749288) Empty map so there are no back handlers and back can close
                    // customizer

                    // TODO(b/330200163) Add an Up from Bottom to be able to collapse the shade
                    // while customizing
                } else {
                    put(Back, UserActionResult(backScene))
                    put(Swipe(SwipeDirection.Up), UserActionResult(backScene))
                    put(
                        Swipe(fromSource = Edge.Bottom, direction = SwipeDirection.Up),
                        UserActionResult(SceneFamilies.Home),
                    )
                }
            }
        }

    val isMediaVisible: StateFlow<Boolean> = mediaCarouselInteractor.hasAnyMediaOrRecommendation

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }
}
