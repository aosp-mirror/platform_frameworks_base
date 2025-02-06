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

package com.android.systemui.keyboard.stickykeys.ui.viewmodel

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.keyboard.stickykeys.data.repository.StickyKeysRepository
import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class StickyKeysIndicatorViewModel
@Inject
constructor(
    stickyKeysRepository: StickyKeysRepository,
    keyboardRepository: KeyboardRepository,
    @Application applicationScope: CoroutineScope,
) {

    val indicatorContent: Flow<Map<ModifierKey, Locked>> =
        keyboardRepository.isAnyKeyboardConnected
            .flatMapLatest { keyboardPresent ->
                if (keyboardPresent) stickyKeysRepository.settingEnabled else flowOf(false)
            }
            .flatMapLatest { enabled ->
                if (enabled) stickyKeysRepository.stickyKeys else flowOf(emptyMap())
            }
            .stateIn(applicationScope, SharingStarted.Lazily, emptyMap())
}
