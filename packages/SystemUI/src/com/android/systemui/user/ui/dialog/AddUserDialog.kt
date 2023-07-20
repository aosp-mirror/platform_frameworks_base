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
package com.android.systemui.user.ui.dialog

import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.UserHandle
import com.android.settingslib.R
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.user.CreateUserActivity

/** Dialog for adding a new user to the device. */
class AddUserDialog(
    context: Context,
    userHandle: UserHandle,
    isKeyguardShowing: Boolean,
    showEphemeralMessage: Boolean,
    private val falsingManager: FalsingManager,
    private val broadcastSender: BroadcastSender,
    private val dialogLaunchAnimator: DialogLaunchAnimator
) : SystemUIDialog(context) {

    private val onClickListener =
        object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface, which: Int) {
                val penalty =
                    if (which == BUTTON_NEGATIVE) {
                        FalsingManager.NO_PENALTY
                    } else {
                        FalsingManager.MODERATE_PENALTY
                    }
                if (falsingManager.isFalseTap(penalty)) {
                    return
                }

                if (which == BUTTON_NEUTRAL) {
                    cancel()
                    return
                }

                dialogLaunchAnimator.dismissStack(this@AddUserDialog)
                if (ActivityManager.isUserAMonkey()) {
                    return
                }

                // Use broadcast instead of ShadeController, as this dialog may have started in
                // another process where normal dagger bindings are not available.
                broadcastSender.sendBroadcastAsUser(
                    Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    userHandle
                )

                context.startActivityAsUser(
                    CreateUserActivity.createIntentForStart(context),
                    userHandle,
                )
            }
        }

    init {
        setTitle(R.string.user_add_user_title)
        val message =
            context.getString(R.string.user_add_user_message_short) +
                if (showEphemeralMessage) {
                    context.getString(
                        com.android.systemui.R.string.user_add_user_message_guest_remove
                    )
                } else {
                    ""
                }
        setMessage(message)

        setButton(
            BUTTON_NEUTRAL,
            context.getString(android.R.string.cancel),
            onClickListener,
        )

        setButton(
            BUTTON_POSITIVE,
            context.getString(android.R.string.ok),
            onClickListener,
        )

        setWindowOnTop(this, isKeyguardShowing)
    }
}
