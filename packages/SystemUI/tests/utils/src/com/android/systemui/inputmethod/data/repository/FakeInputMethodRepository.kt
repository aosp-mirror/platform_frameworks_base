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

package com.android.systemui.inputmethod.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.inputmethod.data.model.InputMethodModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf

@SysUISingleton
class FakeInputMethodRepository : InputMethodRepository {

    private var usersToEnabledInputMethods: MutableMap<Int, Flow<InputMethodModel>> = mutableMapOf()

    var selectedInputMethodSubtypes = listOf<InputMethodModel.Subtype>()

    /**
     * The display ID on which the input method picker dialog was shown, or `null` if the dialog was
     * not shown.
     */
    var inputMethodPickerShownDisplayId: Int? = null

    fun setEnabledInputMethods(userId: Int, vararg enabledInputMethods: InputMethodModel) {
        usersToEnabledInputMethods[userId] = enabledInputMethods.asFlow()
    }

    override suspend fun enabledInputMethods(
        userId: Int,
        fetchSubtypes: Boolean,
    ): Flow<InputMethodModel> {
        return usersToEnabledInputMethods[userId] ?: flowOf()
    }

    override suspend fun selectedInputMethodSubtypes(): List<InputMethodModel.Subtype> =
        selectedInputMethodSubtypes

    override suspend fun showInputMethodPicker(displayId: Int, showAuxiliarySubtypes: Boolean) {
        inputMethodPickerShownDisplayId = displayId
    }
}
