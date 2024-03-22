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
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ScreenshotViewModel(private val accessibilityManager: AccessibilityManager) {
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview
    private val _previewAction = MutableStateFlow<Runnable?>(null)
    val previewAction: StateFlow<Runnable?> = _previewAction
    private val _actions = MutableStateFlow(emptyList<ActionButtonViewModel>())
    val actions: StateFlow<List<ActionButtonViewModel>> = _actions
    val showDismissButton: Boolean
        get() = accessibilityManager.isEnabled

    fun setScreenshotBitmap(bitmap: Bitmap?) {
        _preview.value = bitmap
    }

    fun setPreviewAction(runnable: Runnable) {
        _previewAction.value = runnable
    }

    fun addActions(actions: List<ActionButtonViewModel>) {
        val actionList = _actions.value.toMutableList()
        actionList.addAll(actions)
        _actions.value = actionList
    }

    fun reset() {
        _preview.value = null
        _previewAction.value = null
        _actions.value = listOf()
    }
}
