/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.user

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.BUTTON_NEUTRAL
import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.UiEventLogger
import com.android.systemui.res.R
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.QSUserSwitcherEvent
import com.android.systemui.qs.tiles.UserDetailView
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.user.ui.dialog.DialogShowerImpl
import javax.inject.Inject
import javax.inject.Provider

/**
 * Controller for [UserDialog].
 */
@SysUISingleton
class UserSwitchDialogController @Inject constructor(
    private val userDetailViewAdapterProvider: Provider<UserDetailView.Adapter>,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val uiEventLogger: UiEventLogger,
    private val dialogFactory: SystemUIDialog.Factory
) {

    companion object {
        private const val INTERACTION_JANK_TAG = "switch_user"
        private val USER_SETTINGS_INTENT = Intent(Settings.ACTION_USER_SETTINGS)
    }

    /**
     * Show a [UserDialog].
     *
     * Populate the dialog with information from and adapter obtained from
     * [userDetailViewAdapterProvider] and show it as launched from [expandable].
     */
    fun showDialog(context: Context, expandable: Expandable) {
        with(dialogFactory.create()) {
            setShowForAllUsers(true)
            setCanceledOnTouchOutside(true)

            setTitle(R.string.qs_user_switch_dialog_title)
            setPositiveButton(R.string.quick_settings_done) { _, _ ->
                uiEventLogger.log(QSUserSwitcherEvent.QS_USER_DETAIL_CLOSE)
            }
            setNeutralButton(R.string.quick_settings_more_user_settings, { _, _ ->
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    uiEventLogger.log(QSUserSwitcherEvent.QS_USER_MORE_SETTINGS)
                    val controller = dialogTransitionAnimator.createActivityTransitionController(
                        getButton(BUTTON_NEUTRAL)
                    )

                    if (controller == null) {
                        dismiss()
                    }

                    activityStarter.postStartActivityDismissingKeyguard(
                        USER_SETTINGS_INTENT, 0, controller
                    )
                }
            }, false /* dismissOnClick */)
            val gridFrame = LayoutInflater.from(this.context)
                .inflate(R.layout.qs_user_dialog_content, null)
            setView(gridFrame)

            val adapter = userDetailViewAdapterProvider.get()

            adapter.linkToViewGroup(gridFrame.findViewById(R.id.grid))

            val controller =
                expandable.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                )
            if (controller != null) {
                dialogTransitionAnimator.show(
                    this,
                    controller,
                )
            } else {
                show()
            }

            uiEventLogger.log(QSUserSwitcherEvent.QS_USER_DETAIL_OPEN)
            adapter.injectDialogShower(DialogShowerImpl(this, dialogTransitionAnimator))
        }
    }

    interface DialogShower : DialogInterface {
        fun showDialog(dialog: Dialog, cuj: DialogCuj)
    }
}
