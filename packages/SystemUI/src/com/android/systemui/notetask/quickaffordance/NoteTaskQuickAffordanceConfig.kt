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
import android.hardware.input.InputSettings
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.LockScreenState
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.OnTriggeredResult
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.PickerScreenState
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEnabledKey
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.stylus.StylusManager
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class NoteTaskQuickAffordanceConfig
@Inject
constructor(
    context: Context,
    private val controller: NoteTaskController,
    private val stylusManager: StylusManager,
    private val lazyRepository: Lazy<KeyguardQuickAffordanceRepository>,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
) : KeyguardQuickAffordanceConfig {

    override val key = BuiltInKeyguardQuickAffordanceKeys.CREATE_NOTE

    private val pickerNameResourceId = R.string.note_task_button_label

    override val pickerName: String = context.getString(pickerNameResourceId)

    override val pickerIconResourceId = R.drawable.ic_note_task_shortcut_keyguard

    // Due to a dependency cycle with KeyguardQuickAffordanceRepository, we need to lazily access
    // the repository when lockScreenState is accessed for the first time.
    override val lockScreenState by lazy {
        val stylusEverUsedFlow = createStylusEverUsedFlow(context, stylusManager)
        val configSelectedFlow = createConfigSelectedFlow(lazyRepository.get(), key)
        combine(configSelectedFlow, stylusEverUsedFlow) { isSelected, isStylusEverUsed ->
            if (isEnabled && (isSelected || isStylusEverUsed)) {
                val contentDescription = ContentDescription.Resource(pickerNameResourceId)
                val icon = Icon.Resource(pickerIconResourceId, contentDescription)
                LockScreenState.Visible(icon)
            } else {
                LockScreenState.Hidden
            }
        }
    }

    override suspend fun getPickerScreenState() =
        if (isEnabled) {
            PickerScreenState.Default()
        } else {
            PickerScreenState.UnavailableOnDevice
        }

    override fun onTriggered(expandable: Expandable?): OnTriggeredResult {
        controller.showNoteTask(
            entryPoint = NoteTaskEntryPoint.QUICK_AFFORDANCE,
        )
        return OnTriggeredResult.Handled
    }
}

private fun createStylusEverUsedFlow(context: Context, stylusManager: StylusManager) =
    callbackFlow {
        trySendBlocking(InputSettings.isStylusEverUsed(context))
        val callback =
            object : StylusManager.StylusCallback {
                override fun onStylusFirstUsed() {
                    trySendBlocking(InputSettings.isStylusEverUsed(context))
                }
            }
        stylusManager.registerCallback(callback)
        awaitClose { stylusManager.unregisterCallback(callback) }
    }

private fun createConfigSelectedFlow(repository: KeyguardQuickAffordanceRepository, key: String) =
    repository.selections.map { selected ->
        selected.values.flatten().any { selectedConfig -> selectedConfig.key == key }
    }
