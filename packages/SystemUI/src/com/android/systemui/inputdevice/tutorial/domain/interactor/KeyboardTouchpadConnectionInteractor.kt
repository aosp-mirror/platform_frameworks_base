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

package com.android.systemui.inputdevice.tutorial.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@SysUISingleton
class KeyboardTouchpadConnectionInteractor
@Inject
constructor(
    keyboardRepository: KeyboardRepository,
    touchpadRepository: TouchpadRepository,
) {

    val connectionState: Flow<ConnectionState> =
        combine(
            keyboardRepository.isAnyKeyboardConnected,
            touchpadRepository.isAnyTouchpadConnected
        ) { keyboardConnected, touchpadConnected ->
            ConnectionState(keyboardConnected, touchpadConnected)
        }
}

data class ConnectionState(val keyboardConnected: Boolean, val touchpadConnected: Boolean)
