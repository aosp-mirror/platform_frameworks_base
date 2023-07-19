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

package com.android.systemui.user.domain.model

import android.os.UserHandle
import com.android.systemui.qs.user.UserSwitchDialogController

/** Encapsulates a request to show a dialog. */
sealed class ShowDialogRequestModel(
    open val dialogShower: UserSwitchDialogController.DialogShower? = null,
) {
    data class ShowAddUserDialog(
        val userHandle: UserHandle,
        val isKeyguardShowing: Boolean,
        val showEphemeralMessage: Boolean,
        override val dialogShower: UserSwitchDialogController.DialogShower?,
    ) : ShowDialogRequestModel(dialogShower)

    data class ShowUserCreationDialog(
        val isGuest: Boolean,
    ) : ShowDialogRequestModel()

    data class ShowExitGuestDialog(
        val guestUserId: Int,
        val targetUserId: Int,
        val isGuestEphemeral: Boolean,
        val isKeyguardShowing: Boolean,
        val onExitGuestUser: (guestId: Int, targetId: Int, forceRemoveGuest: Boolean) -> Unit,
        override val dialogShower: UserSwitchDialogController.DialogShower?,
    ) : ShowDialogRequestModel(dialogShower)

    /** Show the user switcher dialog */
    object ShowUserSwitcherDialog : ShowDialogRequestModel()
}
