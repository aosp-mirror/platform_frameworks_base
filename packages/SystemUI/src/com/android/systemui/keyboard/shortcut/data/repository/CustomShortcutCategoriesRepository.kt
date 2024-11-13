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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.KeyTrigger
import android.hardware.input.InputManager
import android.hardware.input.InputSettings
import android.hardware.input.KeyGestureEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@SysUISingleton
class CustomShortcutCategoriesRepository
@Inject
constructor(
    stateRepository: ShortcutHelperStateRepository,
    private val userTracker: UserTracker,
    @Background private val backgroundScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val shortcutCategoriesUtils: ShortcutCategoriesUtils,
    private val context: Context,
) : ShortcutCategoriesRepository {

    private val userContext: Context
        get() = userTracker.createCurrentUserContext(userTracker.userContext)

    // Input manager created with user context to provide correct user id when requesting custom
    // shortcut
    private val inputManager: InputManager
        get() = userContext.getSystemService(INPUT_SERVICE) as InputManager

    private val activeInputDevice =
        stateRepository.state.map {
            if (it is Active) {
                withContext(backgroundDispatcher) { inputManager.getInputDevice(it.deviceId) }
            } else {
                null
            }
        }

    override val categories: Flow<List<ShortcutCategory>> =
        activeInputDevice
            .map { inputDevice ->
                if (inputDevice == null) {
                    emptyList()
                } else {
                    val customInputGesturesForUser: List<InputGestureData> =
                        if (InputSettings.isCustomizableInputGesturesFeatureFlagEnabled()) {
                            inputManager.getCustomInputGestures(/* filter= */ null)
                        } else emptyList()
                    val sources = toInternalGroupSources(customInputGesturesForUser)
                    val supportedKeyCodes =
                        shortcutCategoriesUtils.fetchSupportedKeyCodes(
                            inputDevice.id,
                            sources.map { it.groups },
                        )

                    val result =
                        sources.mapNotNull { source ->
                            shortcutCategoriesUtils.fetchShortcutCategory(
                                type = source.type,
                                groups = source.groups,
                                inputDevice = inputDevice,
                                supportedKeyCodes = supportedKeyCodes,
                            )
                        }
                    result
                }
            }
            .stateIn(
                scope = backgroundScope,
                initialValue = emptyList(),
                started = SharingStarted.Lazily,
            )

    private fun toInternalGroupSources(
        inputGestures: List<InputGestureData>
    ): List<InternalGroupsSource> {
        val ungroupedInternalGroupSources =
            inputGestures.mapNotNull { gestureData ->
                val keyTrigger = gestureData.trigger as KeyTrigger
                val keyGestureType = gestureData.action.keyGestureType()
                fetchGroupLabelByGestureType(keyGestureType)?.let { groupLabel ->
                    toInternalKeyboardShortcutInfo(keyGestureType, keyTrigger)?.let {
                        internalKeyboardShortcutInfo ->
                        val group =
                            InternalKeyboardShortcutGroup(
                                label = groupLabel,
                                items = listOf(internalKeyboardShortcutInfo),
                            )

                        fetchShortcutCategoryTypeByGestureType(keyGestureType)?.let {
                            InternalGroupsSource(groups = listOf(group), type = it)
                        }
                    }
                }
            }

        return ungroupedInternalGroupSources
    }

    private fun toInternalKeyboardShortcutInfo(
        keyGestureType: Int,
        keyTrigger: KeyTrigger,
    ): InternalKeyboardShortcutInfo? {
        fetchShortcutInfoLabelByGestureType(keyGestureType)?.let {
            return InternalKeyboardShortcutInfo(
                label = it,
                keycode = keyTrigger.keycode,
                modifiers = keyTrigger.modifierState,
                isCustomShortcut = true,
            )
        }
        return null
    }

    private fun fetchGroupLabelByGestureType(
        @KeyGestureEvent.KeyGestureType keyGestureType: Int
    ): String? {
        InputGestures.gestureToInternalKeyboardShortcutGroupLabelResIdMap[keyGestureType]?.let {
            return context.getString(it)
        } ?: return null
    }

    private fun fetchShortcutInfoLabelByGestureType(
        @KeyGestureEvent.KeyGestureType keyGestureType: Int
    ): String? {
        InputGestures.gestureToInternalKeyboardShortcutInfoLabelResIdMap[keyGestureType]?.let {
            return context.getString(it)
        } ?: return null
    }

    private fun fetchShortcutCategoryTypeByGestureType(
        @KeyGestureEvent.KeyGestureType keyGestureType: Int
    ): ShortcutCategoryType? {
        return InputGestures.gestureToShortcutCategoryTypeMap[keyGestureType]
    }

    private data class InternalGroupsSource(
        val groups: List<InternalKeyboardShortcutGroup>,
        val type: ShortcutCategoryType,
    )
}
