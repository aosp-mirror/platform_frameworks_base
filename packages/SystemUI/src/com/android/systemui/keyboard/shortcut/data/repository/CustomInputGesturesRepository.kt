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
import android.hardware.input.InputManager
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
import android.hardware.input.InputSettings
import android.util.Log
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult.ERROR_OTHER
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult.ERROR_RESERVED_COMBINATION
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult.SUCCESS
import com.android.systemui.settings.UserTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class CustomInputGesturesRepository
@Inject
constructor(private val userTracker: UserTracker,
    @Background private val bgCoroutineContext: CoroutineContext)
{

    private val userContext: Context
        get() = userTracker.createCurrentUserContext(userTracker.userContext)

    // Input manager created with user context to provide correct user id when requesting custom
    // shortcut
    private val inputManager: InputManager
        get() = userContext.getSystemService(INPUT_SERVICE) as InputManager

    private val _customInputGesture = MutableStateFlow<List<InputGestureData>>(emptyList())

    val customInputGestures =
        _customInputGesture.onStart { refreshCustomInputGestures() }

    private fun refreshCustomInputGestures() {
        setCustomInputGestures(inputGestures = retrieveCustomInputGestures())
    }

    private fun setCustomInputGestures(inputGestures: List<InputGestureData>) {
        _customInputGesture.value = inputGestures
    }

    fun retrieveCustomInputGestures(): List<InputGestureData> {
        return if (InputSettings.isCustomizableInputGesturesFeatureFlagEnabled()) {
            inputManager.getCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
        } else emptyList()
    }

    suspend fun addCustomInputGesture(inputGesture: InputGestureData): ShortcutCustomizationRequestResult {
        return withContext(bgCoroutineContext) {
            when (val result = inputManager.addCustomInputGesture(inputGesture)) {
                CUSTOM_INPUT_GESTURE_RESULT_SUCCESS -> {
                    refreshCustomInputGestures()
                    SUCCESS
                }
                CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS ->
                    ERROR_RESERVED_COMBINATION

                CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE ->
                    ERROR_RESERVED_COMBINATION

                else -> {
                    Log.w(
                        TAG,
                        "Attempted to add inputGesture: $inputGesture " +
                                "but ran into an error with code: $result",
                    )
                    ERROR_OTHER
                }
            }
        }
    }

    suspend fun deleteCustomInputGesture(inputGesture: InputGestureData): ShortcutCustomizationRequestResult {
        return withContext(bgCoroutineContext){
            when (
                val result = inputManager.removeCustomInputGesture(inputGesture)
            ) {
                CUSTOM_INPUT_GESTURE_RESULT_SUCCESS -> {
                    refreshCustomInputGestures()
                    SUCCESS
                }
                else -> {
                    Log.w(
                        TAG,
                        "Attempted to delete inputGesture: $inputGesture " +
                                "but ran into an error with code: $result",
                    )
                    ERROR_OTHER
                }
            }
        }
    }

    suspend fun resetAllCustomInputGestures(): ShortcutCustomizationRequestResult {
        return withContext(bgCoroutineContext) {
            try {
                inputManager.removeAllCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
                setCustomInputGestures(emptyList())
                SUCCESS
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Attempted to remove all custom shortcut but ran into a remote error: $e",
                )
                ERROR_OTHER
            }
        }
    }

    private companion object {
        private const val TAG = "CustomInputGesturesRepository"
    }
}