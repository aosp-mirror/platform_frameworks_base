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

import android.annotation.UserIdInt
import android.content.Context
import android.content.DialogInterface
import com.android.settingslib.R
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.phone.SystemUIDialog

/** Dialog for exiting the guest user. */
class ExitGuestDialog(
    context: Context,
    private val guestUserId: Int,
    private val isGuestEphemeral: Boolean,
    private val targetUserId: Int,
    isKeyguardShowing: Boolean,
    private val falsingManager: FalsingManager,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val onExitGuestUserListener: OnExitGuestUserListener,
) : SystemUIDialog(context) {

    fun interface OnExitGuestUserListener {
        fun onExitGuestUser(
            @UserIdInt guestId: Int,
            @UserIdInt targetId: Int,
            forceRemoveGuest: Boolean,
        )
    }

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

                if (isGuestEphemeral) {
                    if (which == BUTTON_POSITIVE) {
                        dialogTransitionAnimator.dismissStack(this@ExitGuestDialog)
                        // Ephemeral guest: exit guest, guest is removed by the system
                        // on exit, since its marked ephemeral
                        onExitGuestUserListener.onExitGuestUser(guestUserId, targetUserId, false)
                    } else if (which == BUTTON_NEGATIVE) {
                        // Cancel clicked, do nothing
                        cancel()
                    }
                } else {
                    when (which) {
                        BUTTON_POSITIVE -> {
                            dialogTransitionAnimator.dismissStack(this@ExitGuestDialog)
                            // Non-ephemeral guest: exit guest, guest is not removed by the system
                            // on exit, since its marked non-ephemeral
                            onExitGuestUserListener.onExitGuestUser(
                                guestUserId,
                                targetUserId,
                                false
                            )
                        }
                        BUTTON_NEGATIVE -> {
                            dialogTransitionAnimator.dismissStack(this@ExitGuestDialog)
                            // Non-ephemeral guest: remove guest and then exit
                            onExitGuestUserListener.onExitGuestUser(guestUserId, targetUserId, true)
                        }
                        BUTTON_NEUTRAL -> {
                            // Cancel clicked, do nothing
                            cancel()
                        }
                    }
                }
            }
        }

    init {
        if (isGuestEphemeral) {
            setTitle(context.getString(R.string.guest_exit_dialog_title))
            setMessage(context.getString(R.string.guest_exit_dialog_message))
            setButton(
                BUTTON_NEUTRAL,
                context.getString(android.R.string.cancel),
                onClickListener,
            )
            setButton(
                BUTTON_POSITIVE,
                context.getString(R.string.guest_exit_dialog_button),
                onClickListener,
            )
        } else {
            setTitle(context.getString(R.string.guest_exit_dialog_title_non_ephemeral))
            setMessage(context.getString(R.string.guest_exit_dialog_message_non_ephemeral))
            setButton(
                BUTTON_NEUTRAL,
                context.getString(android.R.string.cancel),
                onClickListener,
            )
            setButton(
                BUTTON_NEGATIVE,
                context.getString(R.string.guest_exit_clear_data_button),
                onClickListener,
            )
            setButton(
                BUTTON_POSITIVE,
                context.getString(R.string.guest_exit_save_data_button),
                onClickListener,
            )
        }
        setWindowOnTop(this, isKeyguardShowing)
        setCanceledOnTouchOutside(false)
    }
}
