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
 */

package com.android.systemui.notetask.quickaffordance

import android.content.Context
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.LockScreenState
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.OnTriggeredResult
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.PickerScreenState
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskController.ShowNoteTaskUiEvent
import com.android.systemui.notetask.NoteTaskEnabledKey
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOf

internal class NoteTaskQuickAffordanceConfig
@Inject
constructor(
    context: Context,
    private val noteTaskController: NoteTaskController,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
) : KeyguardQuickAffordanceConfig {

    override val key = BuiltInKeyguardQuickAffordanceKeys.CREATE_NOTE

    override val pickerName: String = context.getString(R.string.note_task_button_label)

    override val pickerIconResourceId = R.drawable.ic_note_task_shortcut_keyguard

    override val lockScreenState = flowOf(getLockScreenState())

    // TODO(b/265949213)
    private fun getLockScreenState() =
        if (isEnabled) {
            val icon = Icon.Resource(pickerIconResourceId, ContentDescription.Loaded(pickerName))
            LockScreenState.Visible(icon)
        } else {
            LockScreenState.Hidden
        }

    override suspend fun getPickerScreenState() =
        if (isEnabled) {
            PickerScreenState.Default()
        } else {
            PickerScreenState.UnavailableOnDevice
        }

    override fun onTriggered(expandable: Expandable?): OnTriggeredResult {
        noteTaskController.showNoteTask(
            uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE
        )
        return OnTriggeredResult.Handled
    }
}
