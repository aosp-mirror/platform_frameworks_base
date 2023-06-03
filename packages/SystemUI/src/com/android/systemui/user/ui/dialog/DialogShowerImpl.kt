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
 */

package com.android.systemui.user.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.qs.user.UserSwitchDialogController.DialogShower

/** Extracted from [UserSwitchDialogController] */
class DialogShowerImpl(
    private val animateFrom: Dialog,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
) : DialogInterface by animateFrom, DialogShower {
    override fun showDialog(dialog: Dialog, cuj: DialogCuj) {
        dialogLaunchAnimator.showFromDialog(dialog, animateFrom = animateFrom, cuj)
    }
}
