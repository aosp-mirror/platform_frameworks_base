/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.inputdevice.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.data.model.UserDeviceConnectionStatus
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Allow listening keyboard and touchpad device connection changes for current user. It emits new
 * value when user is changed.
 */
@SysUISingleton
class UserInputDeviceRepository
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    keyboardRepository: KeyboardRepository,
    touchpadRepository: TouchpadRepository,
    userRepository: UserRepository,
) {
    private val selectedUserId =
        userRepository.selectedUser
            .filter { it.selectionStatus == SelectionStatus.SELECTION_COMPLETE }
            .map { it.userInfo.id }

    val isAnyKeyboardConnectedForUser =
        keyboardRepository.isAnyKeyboardConnected
            .combine(selectedUserId) { isAnyKeyboardConnected, userId ->
                UserDeviceConnectionStatus(isAnyKeyboardConnected, userId)
            }
            .flowOn(backgroundDispatcher)

    val isAnyTouchpadConnectedForUser =
        touchpadRepository.isAnyTouchpadConnected
            .combine(selectedUserId) { isAnyTouchpadConnected, userId ->
                UserDeviceConnectionStatus(isAnyTouchpadConnected, userId)
            }
            .flowOn(backgroundDispatcher)
}
