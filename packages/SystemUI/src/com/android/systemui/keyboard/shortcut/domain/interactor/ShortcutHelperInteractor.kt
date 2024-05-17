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

package com.android.systemui.keyboard.shortcut.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperRepository
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState
import com.android.systemui.model.SysUiState
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shared.system.QuickStepContract
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@SysUISingleton
class ShortcutHelperInteractor
@Inject
constructor(
    private val displayTracker: DisplayTracker,
    @Background private val backgroundScope: CoroutineScope,
    private val sysUiState: SysUiState,
    private val repository: ShortcutHelperRepository
) {

    val state: Flow<ShortcutHelperState> = repository.state

    fun onViewClosed() {
        repository.hide()
        setSysUiStateFlagEnabled(false)
    }

    fun onViewOpened() {
        setSysUiStateFlagEnabled(true)
    }

    private fun setSysUiStateFlagEnabled(enabled: Boolean) {
        backgroundScope.launch {
            sysUiState
                .setFlag(QuickStepContract.SYSUI_STATE_SHORTCUT_HELPER_SHOWING, enabled)
                .commitUpdate(displayTracker.defaultDisplayId)
        }
    }
}
