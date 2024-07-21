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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.view.View
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for a view model that knows the display requirements for a single type of ongoing
 * activity chip.
 */
interface OngoingActivityChipViewModel {
    /** A flow modeling the chip that should be shown (or not shown). */
    val chip: StateFlow<OngoingActivityChipModel>

    companion object {
        /** Creates a chip click listener that launches a dialog created by [dialogDelegate]. */
        fun createDialogLaunchOnClickListener(
            dialogDelegate: SystemUIDialog.Delegate,
            dialogTransitionAnimator: DialogTransitionAnimator,
            cuj: DialogCuj,
            @StatusBarChipsLog logger: LogBuffer,
            tag: String,
        ): View.OnClickListener {
            return View.OnClickListener { view ->
                logger.log(tag, LogLevel.INFO, {}, { "Chip clicked" })
                val dialog = dialogDelegate.createDialog()
                val launchableView =
                    view.requireViewById<ChipBackgroundContainer>(
                        R.id.ongoing_activity_chip_background
                    )
                dialogTransitionAnimator.showFromView(dialog, launchableView, cuj)
            }
        }
    }
}
