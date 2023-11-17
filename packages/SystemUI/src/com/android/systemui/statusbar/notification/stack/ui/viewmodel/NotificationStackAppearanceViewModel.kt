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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** ViewModel which represents the state of the NSSL/Controller in the world of flexiglass */
@SysUISingleton
class NotificationStackAppearanceViewModel
@Inject
constructor(
    stackAppearanceInteractor: NotificationStackAppearanceInteractor,
    shadeInteractor: ShadeInteractor,
) {
    /** The expansion fraction from the top of the notification shade */
    val expandFraction: Flow<Float> = shadeInteractor.shadeExpansion

    /** The position of the notification stack in the current scene */
    val stackPosition: Flow<SharedNotificationContainerPosition> =
        stackAppearanceInteractor.stackPosition
}
