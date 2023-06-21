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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.R
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.keyguard.domain.interactor.KeyguardLongPressInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Models UI state to support the lock screen long-press feature. */
class KeyguardLongPressViewModel
@Inject
constructor(
    private val interactor: KeyguardLongPressInteractor,
) {

    /** Whether the long-press handling feature should be enabled. */
    val isLongPressHandlingEnabled: Flow<Boolean> = interactor.isLongPressHandlingEnabled

    /** View-model for a menu that should be shown; `null` when no menu should be shown. */
    val menu: Flow<KeyguardSettingsPopupMenuViewModel?> =
        interactor.menu.map { model ->
            model?.let {
                KeyguardSettingsPopupMenuViewModel(
                    icon =
                        Icon.Resource(
                            res = R.drawable.ic_settings,
                            contentDescription = null,
                        ),
                    text =
                        Text.Resource(
                            res = R.string.lock_screen_settings,
                        ),
                    position = model.position,
                    onClicked = model.onClicked,
                    onDismissed = model.onDismissed,
                )
            }
        }

    /** Notifies that the user has long-pressed on the lock screen. */
    fun onLongPress(
        x: Int,
        y: Int,
    ) {
        interactor.onLongPress(
            x = x,
            y = y,
        )
    }
}
