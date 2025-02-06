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

package com.android.systemui.keyboard.backlight.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.keyboard.shared.model.BacklightModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Allows listening to changes to keyboard backlight level */
@SysUISingleton
class KeyboardBacklightInteractor
@Inject
constructor(
    private val keyboardRepository: KeyboardRepository,
) {

    /** Emits current backlight level as [BacklightModel] or null if keyboard is not connected */
    val backlight: Flow<BacklightModel?> =
        keyboardRepository.isAnyKeyboardConnected.flatMapLatest { connected ->
            if (connected) keyboardRepository.backlight else flowOf(null)
        }
}
