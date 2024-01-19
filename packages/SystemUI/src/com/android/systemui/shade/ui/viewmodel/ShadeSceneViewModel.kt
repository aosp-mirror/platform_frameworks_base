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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) {
    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: StateFlow<SceneKey> =
        combine(
                deviceEntryInteractor.isUnlocked,
                deviceEntryInteractor.canSwipeToEnter,
            ) { isUnlocked, canSwipeToDismiss ->
                upDestinationSceneKey(
                    isUnlocked = isUnlocked,
                    canSwipeToDismiss = canSwipeToDismiss,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    upDestinationSceneKey(
                        isUnlocked = deviceEntryInteractor.isUnlocked.value,
                        canSwipeToDismiss = deviceEntryInteractor.canSwipeToEnter.value,
                    ),
            )

    /** Notifies that some content in the shade was clicked. */
    fun onContentClicked() = deviceEntryInteractor.attemptDeviceEntry()

    private fun upDestinationSceneKey(
        isUnlocked: Boolean,
        canSwipeToDismiss: Boolean?,
    ): SceneKey {
        return when {
            canSwipeToDismiss == true -> SceneKey.Lockscreen
            isUnlocked -> SceneKey.Gone
            else -> SceneKey.Lockscreen
        }
    }

    fun isMediaVisible(): Boolean {
        // TODO(b/296122467): handle updates to carousel visibility while scene is still visible
        return mediaDataManager.hasActiveMediaOrRecommendation()
    }
}
