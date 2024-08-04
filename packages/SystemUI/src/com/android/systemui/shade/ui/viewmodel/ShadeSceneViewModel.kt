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

package com.android.systemui.shade.ui.viewmodel

import androidx.lifecycle.LifecycleOwner
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.Activatable
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Models UI state and handles user input for the shade scene. */
@SysUISingleton
class ShadeSceneViewModel
@Inject
constructor(
    val qsSceneAdapter: QSSceneAdapter,
    val shadeHeaderViewModel: ShadeHeaderViewModel,
    val brightnessMirrorViewModel: BrightnessMirrorViewModel,
    val mediaCarouselInteractor: MediaCarouselInteractor,
    shadeInteractor: ShadeInteractor,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    private val sceneInteractor: SceneInteractor,
    private val unfoldTransitionInteractor: UnfoldTransitionInteractor,
) : Activatable {
    val destinationScenes: Flow<Map<UserAction, UserActionResult>> =
        combine(
            shadeInteractor.shadeMode,
            qsSceneAdapter.isCustomizerShowing,
        ) { shadeMode, isCustomizerShowing ->
            buildMap {
                if (!isCustomizerShowing) {
                    set(
                        Swipe(SwipeDirection.Up),
                        UserActionResult(
                            SceneFamilies.Home,
                            ToSplitShade.takeIf { shadeMode is ShadeMode.Split }
                        )
                    )
                }

                // TODO(b/330200163) Add an else to be able to collapse the shade while customizing
                if (shadeMode is ShadeMode.Single) {
                    set(Swipe(SwipeDirection.Down), UserActionResult(Scenes.QuickSettings))
                }
            }
        }

    private val upDestinationSceneKey: Flow<SceneKey?> =
        destinationScenes.map { it[Swipe(SwipeDirection.Up)]?.toScene }

    private val _isClickable = MutableStateFlow(false)
    /** Whether or not the shade container should be clickable. */
    val isClickable: StateFlow<Boolean> = _isClickable.asStateFlow()

    /**
     * Activates the view-model.
     *
     * Serves as an entrypoint to kick off coroutine work that the view-model requires in order to
     * keep its state fresh and/or perform side-effects.
     *
     * Suspends the caller forever as it will keep doing work until canceled.
     *
     * **Must be invoked** when the scene becomes the current scene or when it becomes visible
     * during a transition (the choice is the responsibility of the parent). Similarly, the work
     * must be canceled when the scene stops being visible or the current scene.
     */
    override suspend fun activate() {
        coroutineScope {
            launch {
                upDestinationSceneKey
                    .flatMapLatestConflated { key ->
                        key?.let { sceneInteractor.resolveSceneFamily(key) } ?: flowOf(null)
                    }
                    .map { it == Scenes.Lockscreen }
                    .collectLatest { _isClickable.value = it }
            }
        }
    }

    val shadeMode: StateFlow<ShadeMode> = shadeInteractor.shadeMode

    val isMediaVisible: StateFlow<Boolean> = mediaCarouselInteractor.hasActiveMediaOrRecommendation

    /**
     * Amount of X-axis translation to apply to various elements as the unfolded foldable is folded
     * slightly, in pixels.
     */
    fun unfoldTranslationX(isOnStartSide: Boolean): Flow<Float> {
        return unfoldTransitionInteractor.unfoldTranslationX(isOnStartSide)
    }

    /** Notifies that some content in the shade was clicked. */
    fun onContentClicked() {
        if (!isClickable.value) {
            return
        }

        sceneInteractor.changeScene(Scenes.Lockscreen, "Shade empty content clicked")
    }

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }
}
