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
import android.hardware.input.InputGestureData.Builder
import android.hardware.input.InputGestureData.KeyTrigger
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.InputManager
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
import android.hardware.input.InputSettings
import android.hardware.input.KeyGestureEvent
import android.hardware.input.KeyGestureEvent.KeyGestureType
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import com.android.systemui.Flags.shortcutHelperKeyGlyph
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
    @Background private val bgCoroutineContext: CoroutineContext,
    private val shortcutCategoriesUtils: ShortcutCategoriesUtils,
    private val context: Context,
    private val inputGestureMaps: InputGestureMaps,
) : ShortcutCategoriesRepository {

    private val userContext: Context
        get() = userTracker.createCurrentUserContext(userTracker.userContext)

    // Input manager created with user context to provide correct user id when requesting custom
    // shortcut
    private val inputManager: InputManager
        get() = userContext.getSystemService(INPUT_SERVICE) as InputManager

    private val _selectedKeyCombination = MutableStateFlow<KeyCombination?>(null)
    private val _shortcutBeingCustomized = mutableStateOf<ShortcutCustomizationRequestInfo?>(null)

    private val activeInputDevice =
        stateRepository.state.map {
            if (it is Active) {
                withContext(bgCoroutineContext) { inputManager.getInputDevice(it.deviceId) }
            } else {
                null
            }
        }

    val pressedKeys =
        _selectedKeyCombination
            .combine(activeInputDevice) { keyCombination, inputDevice ->
                if (inputDevice == null || keyCombination == null) {
                    return@combine emptyList()
                } else {
                    val keyGlyphMap =
                        if (shortcutHelperKeyGlyph()) {
                            inputManager.getKeyGlyphMap(inputDevice.id)
                        } else null
                    val modifiers =
                        shortcutCategoriesUtils.toShortcutModifierKeys(
                            keyCombination.modifiers,
                            keyGlyphMap,
                        )
                    val triggerKey =
                        keyCombination.keyCode?.let {
                            shortcutCategoriesUtils.toShortcutKey(
                                keyGlyphMap,
                                inputDevice.keyCharacterMap,
                                keyCode = it,
                            )
                        }
                    val keys = mutableListOf<ShortcutKey>()
                    modifiers?.let { keys += it }
                    triggerKey?.let { keys += it }
                    return@combine keys
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = emptyList(),
            )

    override val categories: Flow<List<ShortcutCategory>> =
        activeInputDevice
            .map { inputDevice ->
                if (inputDevice == null) {
                    emptyList()
                } else {
                    val customInputGesturesForUser: List<InputGestureData> =
                        getCustomInputGestures()
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

    fun updateUserKeyCombination(keyCombination: KeyCombination?) {
        _selectedKeyCombination.value = keyCombination
    }

    fun onCustomizationRequested(requestInfo: ShortcutCustomizationRequestInfo?) {
        _shortcutBeingCustomized.value = requestInfo
    }

    @VisibleForTesting
    fun buildInputGestureDataForShortcutBeingCustomized(): InputGestureData? {
        try {
            return Builder()
                .addKeyGestureTypeFromShortcutLabel()
                .addTriggerFromSelectedKeyCombination()
                .build()
            // TODO(b/379648200) add app launch data after dynamic label/icon mapping implementation
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "could not add custom shortcut: $e")
            return null
        }
    }

    private fun retrieveInputGestureDataForShortcutBeingDeleted(): InputGestureData? {
        val keyGestureType = getKeyGestureTypeFromShortcutBeingDeletedLabel()
        return getCustomInputGestures().firstOrNull { it.action.keyGestureType() == keyGestureType }
    }

    suspend fun confirmAndSetShortcutCurrentlyBeingCustomized():
        ShortcutCustomizationRequestResult {
        return withContext(bgCoroutineContext) {
            val inputGestureData =
                buildInputGestureDataForShortcutBeingCustomized()
                    ?: return@withContext ShortcutCustomizationRequestResult.ERROR_OTHER

            return@withContext when (inputManager.addCustomInputGesture(inputGestureData)) {
                CUSTOM_INPUT_GESTURE_RESULT_SUCCESS -> ShortcutCustomizationRequestResult.SUCCESS
                CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS ->
                    ShortcutCustomizationRequestResult.ERROR_RESERVED_COMBINATION

                CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE ->
                    ShortcutCustomizationRequestResult.ERROR_RESERVED_COMBINATION

                else -> ShortcutCustomizationRequestResult.ERROR_OTHER
            }
        }
    }

    suspend fun deleteShortcutCurrentlyBeingCustomized():
        ShortcutCustomizationRequestResult {
        return withContext(bgCoroutineContext) {
            val inputGestureData =
                retrieveInputGestureDataForShortcutBeingDeleted()
                    ?: return@withContext ShortcutCustomizationRequestResult.ERROR_OTHER
            return@withContext when (
                val result = inputManager.removeCustomInputGesture(inputGestureData)
            ) {
                CUSTOM_INPUT_GESTURE_RESULT_SUCCESS -> ShortcutCustomizationRequestResult.SUCCESS
                else -> {
                    Log.w(
                        TAG,
                        "Attempted to delete shortcut being customized " +
                            "${_shortcutBeingCustomized.value} but ran into an error. InputGestureData" +
                            " = $inputGestureData, error code: $result",
                    )
                    ShortcutCustomizationRequestResult.ERROR_OTHER
                }
            }
        }
    }

    private fun getCustomInputGestures(): List<InputGestureData> {
        return if (InputSettings.isCustomizableInputGesturesFeatureFlagEnabled()) {
            inputManager.getCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
        } else emptyList()
    }

    private fun Builder.addKeyGestureTypeFromShortcutLabel(): Builder {
        val keyGestureType = getKeyGestureTypeFromShortcutBeingCustomizedLabel()

        if (keyGestureType == null) {
            Log.w(
                TAG,
                "Could not find KeyGestureType for shortcut ${_shortcutBeingCustomized.value}",
            )
            return this
        }

        return setKeyGestureType(keyGestureType)
    }

    @KeyGestureType
    private fun getKeyGestureTypeFromShortcutBeingCustomizedLabel(): Int? {
        val shortcutBeingCustomized =
            getShortcutBeingCustomized() as? ShortcutCustomizationRequestInfo.Add

        if (shortcutBeingCustomized == null) {
            Log.w(
                TAG,
                "Requested key gesture type from label but shortcut being customized is null",
            )
            return null
        }

        return inputGestureMaps.shortcutLabelToKeyGestureTypeMap[shortcutBeingCustomized.label]
    }

    @KeyGestureType
    private fun getKeyGestureTypeFromShortcutBeingDeletedLabel(): Int? {
        val shortcutBeingCustomized =
            getShortcutBeingCustomized() as? ShortcutCustomizationRequestInfo.Delete

        if (shortcutBeingCustomized == null) {
            Log.w(
                TAG,
                "Requested key gesture type from label but shortcut being customized is null",
            )
            return null
        }

        return inputGestureMaps.shortcutLabelToKeyGestureTypeMap[shortcutBeingCustomized.label]
    }

    private fun Builder.addTriggerFromSelectedKeyCombination(): Builder {
        val selectedKeyCombination = _selectedKeyCombination.value
        if (selectedKeyCombination?.keyCode == null) {
            Log.w(
                TAG,
                "User requested to set shortcut but selected key combination is " +
                    "$selectedKeyCombination",
            )
            return this
        }

        return setTrigger(
            createKeyTrigger(
                /* keycode = */ selectedKeyCombination.keyCode,
                /* modifierState = */ shortcutCategoriesUtils.removeUnsupportedModifiers(
                    selectedKeyCombination.modifiers
                ),
            )
        )
    }

    @VisibleForTesting
    fun getShortcutBeingCustomized(): ShortcutCustomizationRequestInfo? {
        return _shortcutBeingCustomized.value
    }

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
        inputGestureMaps.gestureToInternalKeyboardShortcutGroupLabelResIdMap[keyGestureType]?.let {
            return context.getString(it)
        } ?: return null
    }

    private fun fetchShortcutInfoLabelByGestureType(
        @KeyGestureEvent.KeyGestureType keyGestureType: Int
    ): String? {
        inputGestureMaps.gestureToInternalKeyboardShortcutInfoLabelResIdMap[keyGestureType]?.let {
            return context.getString(it)
        } ?: return null
    }

    private fun fetchShortcutCategoryTypeByGestureType(
        @KeyGestureEvent.KeyGestureType keyGestureType: Int
    ): ShortcutCategoryType? {
        return inputGestureMaps.gestureToShortcutCategoryTypeMap[keyGestureType]
    }

    private data class InternalGroupsSource(
        val groups: List<InternalKeyboardShortcutGroup>,
        val type: ShortcutCategoryType,
    )

    private companion object {
        private const val TAG = "CustomShortcutCategoriesRepository"
    }
}
