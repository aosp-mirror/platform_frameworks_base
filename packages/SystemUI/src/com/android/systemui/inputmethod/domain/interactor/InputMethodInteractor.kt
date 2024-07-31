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

package com.android.systemui.inputmethod.domain.interactor

import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.inputmethod.data.repository.InputMethodRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take

/** Hosts application business logic related to input methods (e.g. software keyboard). */
@SysUISingleton
class InputMethodInteractor
@Inject
constructor(
    private val repository: InputMethodRepository,
) {
    /**
     * Returns whether there are multiple enabled input methods to choose from for password input.
     *
     * Method adapted from `com.android.inputmethod.latin.Utils`.
     */
    suspend fun hasMultipleEnabledImesOrSubtypes(userId: Int): Boolean {
        val user = UserHandle.of(userId)
        // Count IMEs that either have no subtypes, or have at least one non-auxiliary subtype.
        val matchingInputMethods =
            repository
                .enabledInputMethods(user, fetchSubtypes = true)
                .filter { ime -> ime.subtypes.isEmpty() || ime.subtypes.any { !it.isAuxiliary } }
                .take(2) // Short-circuit if we find at least 2 matching IMEs.

        return matchingInputMethods.count() > 1 ||
            repository.selectedInputMethodSubtypes(user).size > 1
    }

    /** Shows the system's input method picker dialog. */
    suspend fun showInputMethodPicker(displayId: Int, showAuxiliarySubtypes: Boolean) {
        repository.showInputMethodPicker(displayId, showAuxiliarySubtypes)
    }
}
