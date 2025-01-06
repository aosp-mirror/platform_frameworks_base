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

package com.android.systemui.keyboard.shortcut.data.repository

import android.hardware.input.InputManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class ShortcutHelperInputDeviceRepository
@Inject
constructor(
    stateRepository: ShortcutHelperStateRepository,
    @Background private val backgroundScope: CoroutineScope,
    @Background private val bgCoroutineContext: CoroutineContext,
    private val inputManager: InputManager,
) {
    val activeInputDevice =
        stateRepository.state
            .map {
                if (it is Active) {
                    withContext(bgCoroutineContext) { inputManager.getInputDevice(it.deviceId) }
                } else {
                    null
                }
            }
            .stateIn(scope = backgroundScope, started = SharingStarted.Lazily, initialValue = null)
}
