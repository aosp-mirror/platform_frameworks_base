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
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the quick settings scene. */
@SysUISingleton
class QuickSettingsSceneViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    deviceEntryInteractor: DeviceEntryInteractor,
    val brightnessMirrorViewModel: BrightnessMirrorViewModel,
    val shadeHeaderViewModel: ShadeHeaderViewModel,
    val qsSceneAdapter: QSSceneAdapter,
    val notifications: NotificationsPlaceholderViewModel,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    sceneInteractor: SceneInteractor,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        combine(
                deviceEntryInteractor.isUnlocked,
                deviceEntryInteractor.canSwipeToEnter,
                qsSceneAdapter.isCustomizing,
                sceneInteractor.previousScene(ignored = Scenes.QuickSettings),
            ) { isUnlocked, canSwipeToDismiss, isCustomizing, previousScene ->
                destinationScenes(
                    isUnlocked,
                    canSwipeToDismiss,
                    isCustomizing,
                    previousScene,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    destinationScenes(
                        isUnlocked = deviceEntryInteractor.isUnlocked.value,
                        canSwipeToDismiss = deviceEntryInteractor.canSwipeToEnter.value,
                        isCustomizing = qsSceneAdapter.isCustomizing.value,
                        previousScene = sceneInteractor
                                .previousScene(ignored = Scenes.QuickSettings).value,
                    ),
            )

    private fun destinationScenes(
        isUnlocked: Boolean,
        canSwipeToDismiss: Boolean?,
        isCustomizing: Boolean,
        previousScene: SceneKey?
    ): Map<UserAction, UserActionResult> {
        val upBottomEdge =
            when {
                canSwipeToDismiss == true -> Scenes.Lockscreen
                isUnlocked -> Scenes.Gone
                else -> Scenes.Lockscreen
            }

        return buildMap {
            if (isCustomizing) {
                // TODO(b/332749288) Empty map so there are no back handlers and back can close
                // customizer

                // TODO(b/330200163) Add an Up from Bottom to be able to collapse the shade
                // while customizing
            } else {
                this[Back] = UserActionResult(previousScene ?: Scenes.Shade)
                this[Swipe(SwipeDirection.Up)] = UserActionResult(previousScene ?: Scenes.Shade)
                this[
                    Swipe(
                        fromSource = Edge.Bottom,
                        direction = SwipeDirection.Up,
                    )] = UserActionResult(upBottomEdge)
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
