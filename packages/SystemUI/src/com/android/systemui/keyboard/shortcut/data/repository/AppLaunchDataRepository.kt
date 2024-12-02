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

import android.hardware.input.AppLaunchData
import android.hardware.input.InputGestureData.KeyTrigger
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import com.android.systemui.Flags.shortcutHelperKeyGlyph
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@SysUISingleton
class AppLaunchDataRepository
@Inject
constructor(
    private val inputManager: InputManager,
    @Background private val backgroundScope: CoroutineScope,
    private val shortcutCategoriesUtils: ShortcutCategoriesUtils,
    inputDeviceRepository: ShortcutHelperInputDeviceRepository,
) {

    private val shortcutCommandToAppLaunchDataMap:
        StateFlow<Map<ShortcutCommandKey, AppLaunchData>> =
        inputDeviceRepository.activeInputDevice
            .map { inputDevice ->
                if (inputDevice == null) {
                    emptyMap()
                }
                else{
                    buildCommandToAppLaunchDataMap(inputDevice)
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = mapOf(),
            )

    fun getAppLaunchDataForShortcutWithCommand(shortcutCommand: ShortcutCommand): AppLaunchData? {
        val shortcutCommandAsKey = ShortcutCommandKey(shortcutCommand)
        return shortcutCommandToAppLaunchDataMap.value[shortcutCommandAsKey]
    }

    private fun buildCommandToAppLaunchDataMap(inputDevice: InputDevice):
            Map<ShortcutCommandKey, AppLaunchData> {
        val commandToAppLaunchDataMap =
            mutableMapOf<ShortcutCommandKey, AppLaunchData>()
        val appLaunchInputGestures = inputManager.appLaunchBookmarks
        appLaunchInputGestures.forEach { inputGesture ->
            val keyGlyphMap =
                if (shortcutHelperKeyGlyph()) {
                    inputManager.getKeyGlyphMap(inputDevice.id)
                } else null

            val shortcutCommand =
                shortcutCategoriesUtils.toShortcutCommand(
                    keyGlyphMap,
                    inputDevice.keyCharacterMap,
                    inputGesture.trigger as KeyTrigger,
                )

            if (shortcutCommand != null) {
                commandToAppLaunchDataMap[ShortcutCommandKey(shortcutCommand)] =
                    inputGesture.action.appLaunchData()!!
            } else {
                Log.w(
                    TAG,
                    "could not get Shortcut Command. inputGesture: $inputGesture",
                )
            }
        }

        return commandToAppLaunchDataMap
    }

    private data class ShortcutCommandKey(val keys: List<ShortcutKey>) {
        constructor(
            shortcutCommand: ShortcutCommand
        ) : this(shortcutCommand.keys.sortedBy { it.toString() })
    }

    private companion object {
        private const val TAG = "AppLaunchDataRepository"
    }
}
