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

import com.android.systemui.res.R
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.keyguard.domain.interactor.KeyguardLongPressInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Models the UI state of a keyguard settings popup menu. */
class KeyguardSettingsMenuViewModel
@Inject
constructor(
    private val interactor: KeyguardLongPressInteractor,
) {
    val isVisible: Flow<Boolean> = interactor.isMenuVisible
    val shouldOpenSettings: Flow<Boolean> = interactor.shouldOpenSettings

    val icon: Icon =
        Icon.Resource(
            res = R.drawable.ic_palette,
            contentDescription = null,
        )

    val text: Text =
        Text.Resource(
            res = R.string.lock_screen_settings,
        )

    fun onTouchGestureStarted() {
        interactor.onMenuTouchGestureStarted()
    }

    fun onTouchGestureEnded(isClick: Boolean) {
        interactor.onMenuTouchGestureEnded(
            isClick = isClick,
        )
    }

    fun onSettingsShown() {
        interactor.onSettingsShown()
    }
}
