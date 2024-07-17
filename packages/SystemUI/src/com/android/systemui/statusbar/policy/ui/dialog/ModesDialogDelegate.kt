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

package com.android.systemui.statusbar.policy.ui.dialog

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import javax.inject.Inject

class ModesDialogDelegate
@Inject
constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val activityStarter: ActivityStarter,
) : SystemUIDialog.Delegate {
    override fun createDialog(): SystemUIDialog {
        return sysuiDialogFactory.create { dialog ->
            AlertDialogContent(
                title = { Text(stringResource(R.string.zen_modes_dialog_title)) },
                content = { Text("Under construction") },
                neutralButton = {
                    PlatformOutlinedButton(
                        onClick = {
                            val animationController =
                                dialogTransitionAnimator.createActivityTransitionController(
                                    dialog.getButton(SystemUIDialog.BUTTON_NEUTRAL)
                                )
                            if (animationController == null) {
                                // The controller will take care of dismissing for us after the
                                // animation, but let's make sure we dismiss the dialog if we don't
                                // animate it.
                                dialog.dismiss()
                            }
                            activityStarter.startActivity(
                                ZEN_MODE_SETTINGS_INTENT,
                                true /* dismissShade */,
                                animationController
                            )
                        }
                    ) {
                        Text(stringResource(R.string.zen_modes_dialog_settings))
                    }
                },
                positiveButton = {
                    PlatformButton(onClick = { dialog.dismiss() }) {
                        Text(stringResource(R.string.zen_modes_dialog_done))
                    }
                },
            )
        }
    }

    companion object {
        private val ZEN_MODE_SETTINGS_INTENT = Intent(Settings.ACTION_ZEN_MODE_SETTINGS)
    }
}
