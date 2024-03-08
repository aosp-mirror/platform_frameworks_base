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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade.ui.viewmodel

import androidx.lifecycle.LifecycleOwner
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the shade scene. */
@SysUISingleton
class ShadeSceneViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    val qsSceneAdapter: QSSceneAdapter,
    val shadeHeaderViewModel: ShadeHeaderViewModel,
    val notifications: NotificationsPlaceholderViewModel,
    val mediaDataManager: MediaDataManager,
    shadeInteractor: ShadeInteractor,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
) {
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        combine(
                deviceEntryInteractor.isUnlocked,
                deviceEntryInteractor.canSwipeToEnter,
                shadeInteractor.isSplitShade,
            ) { isUnlocked, canSwipeToDismiss, isSplitShade ->
                destinationScenes(
                    isUnlocked = isUnlocked,
                    canSwipeToDismiss = canSwipeToDismiss,
                    isSplitShade = isSplitShade,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    destinationScenes(
                        isUnlocked = deviceEntryInteractor.isUnlocked.value,
                        canSwipeToDismiss = deviceEntryInteractor.canSwipeToEnter.value,
                        isSplitShade = shadeInteractor.isSplitShade.value,
                    ),
            )

    private val upDestinationSceneKey: Flow<SceneKey?> =
        destinationScenes.map { it[Swipe(SwipeDirection.Up)]?.toScene }

    /** Whether or not the shade container should be clickable. */
    val isClickable: StateFlow<Boolean> =
        upDestinationSceneKey
            .map { it == Scenes.Lockscreen }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false
            )

    /** Whether the current configuration requires the split shade to be shown. */
    val isSplitShade: StateFlow<Boolean> = shadeInteractor.isSplitShade

    /** Notifies that some content in the shade was clicked. */
    fun onContentClicked() = deviceEntryInteractor.attemptDeviceEntry()

    fun isMediaVisible(): Boolean {
        // TODO(b/296122467): handle updates to carousel visibility while scene is still visible
        return mediaDataManager.hasActiveMediaOrRecommendation()
    }

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }

    private fun destinationScenes(
        isUnlocked: Boolean,
        canSwipeToDismiss: Boolean?,
        isSplitShade: Boolean,
    ): Map<UserAction, UserActionResult> {
        val up =
            when {
                canSwipeToDismiss == true -> Scenes.Lockscreen
                isUnlocked -> Scenes.Gone
                else -> Scenes.Lockscreen
            }

        val down = if (isSplitShade) null else Scenes.QuickSettings

        return buildMap {
            this[Swipe(SwipeDirection.Up)] = UserActionResult(up)
            down?.let { this[Swipe(SwipeDirection.Down)] = UserActionResult(down) }
        }
    }
}
