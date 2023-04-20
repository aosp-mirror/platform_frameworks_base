/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.user.ui.viewmodel

import android.content.Context
import android.graphics.drawable.Drawable
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.user.domain.interactor.UserInteractor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarUserChipViewModel
@Inject
constructor(
    @Application private val context: Context,
    interactor: UserInteractor,
) {
    /** Whether the status bar chip ui should be available */
    val chipEnabled: Boolean = interactor.isStatusBarUserChipEnabled

    /** Whether or not the chip should be showing, based on the number of users */
    val isChipVisible: Flow<Boolean> =
        if (!chipEnabled) {
            flowOf(false)
        } else {
            interactor.users.mapLatest { users -> users.size > 1 }
        }

    /** The display name of the current user */
    val userName: Flow<Text> = interactor.selectedUser.mapLatest { userModel -> userModel.name }

    /** Avatar for the current user */
    val userAvatar: Flow<Drawable> =
        interactor.selectedUser.mapLatest { userModel -> userModel.image }

    /** Action to execute on click. Should launch the user switcher */
    val onClick: (Expandable) -> Unit = { interactor.showUserSwitcher(context, it) }
}
