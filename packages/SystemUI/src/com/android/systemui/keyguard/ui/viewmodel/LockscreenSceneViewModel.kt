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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the lockscreen scene. */
@SysUISingleton
class LockscreenSceneViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    deviceEntryInteractor: DeviceEntryInteractor,
    communalInteractor: CommunalInteractor,
    val longPress: KeyguardLongPressViewModel,
    val keyguardRoot: KeyguardRootViewModel,
    val notifications: NotificationsPlaceholderViewModel,
) {
    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: StateFlow<SceneKey> =
        deviceEntryInteractor.isUnlocked
            .map { isUnlocked -> upDestinationSceneKey(isUnlocked) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = upDestinationSceneKey(deviceEntryInteractor.isUnlocked.value),
            )

    private fun upDestinationSceneKey(isUnlocked: Boolean): SceneKey {
        return if (isUnlocked) SceneKey.Gone else SceneKey.Bouncer
    }

    /** The key of the scene we should switch to when swiping left. */
    val leftDestinationSceneKey: SceneKey? =
        if (communalInteractor.isCommunalEnabled) {
            SceneKey.Communal
        } else {
            null
        }
}
