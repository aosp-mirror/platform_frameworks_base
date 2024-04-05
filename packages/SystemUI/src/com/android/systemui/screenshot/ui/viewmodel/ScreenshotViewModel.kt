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

package com.android.systemui.screenshot.ui.viewmodel

import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ScreenshotViewModel(private val accessibilityManager: AccessibilityManager) {
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview
    private val _previewAction = MutableStateFlow<(() -> Unit)?>(null)
    val previewAction: StateFlow<(() -> Unit)?> = _previewAction
    private val _actions = MutableStateFlow(emptyList<ActionButtonViewModel>())
    val actions: StateFlow<List<ActionButtonViewModel>> = _actions
    val showDismissButton: Boolean
        get() = accessibilityManager.isEnabled

    fun setScreenshotBitmap(bitmap: Bitmap?) {
        _preview.value = bitmap
    }

    fun setPreviewAction(onClick: () -> Unit) {
        _previewAction.value = onClick
    }

    fun addAction(actionAppearance: ActionButtonAppearance, onClicked: (() -> Unit)): Int {
        val actionList = _actions.value.toMutableList()
        val action = ActionButtonViewModel.withNextId(actionAppearance, onClicked)
        actionList.add(action)
        _actions.value = actionList
        return action.id
    }

    fun updateActionAppearance(actionId: Int, appearance: ActionButtonAppearance) {
        val actionList = _actions.value.toMutableList()
        val index = actionList.indexOfFirst { it.id == actionId }
        if (index >= 0) {
            actionList[index] =
                ActionButtonViewModel(appearance, actionId, actionList[index].onClicked)
            _actions.value = actionList
        } else {
            Log.w(TAG, "Attempted to update unknown action id $actionId")
        }
    }

    fun removeAction(actionId: Int) {
        val actionList = _actions.value.toMutableList()
        if (actionList.removeIf { it.id == actionId }) {
            // Update if something was removed.
            _actions.value = actionList
        } else {
            Log.w(TAG, "Attempted to remove unknown action id $actionId")
        }
    }

    fun reset() {
        _preview.value = null
        _previewAction.value = null
        _actions.value = listOf()
    }

    companion object {
        const val TAG = "ScreenshotViewModel"
    }
}
