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

import android.annotation.SuppressLint
import android.os.UserHandle
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputmethod.data.model.InputMethodModel
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Provides access to input-method related application state in the bouncer. */
interface InputMethodRepository {
    /**
     * Creates and returns a new `Flow` of installed input methods that are enabled for the
     * specified user.
     *
     * @param fetchSubtypes Whether to fetch the IME Subtypes as well (requires an additional IPC
     *   call for each IME, avoid if not needed).
     * @see InputMethodManager.getEnabledInputMethodListAsUser
     */
    suspend fun enabledInputMethods(userId: Int, fetchSubtypes: Boolean): Flow<InputMethodModel>

    /** Returns enabled subtypes for the currently selected input method. */
    suspend fun selectedInputMethodSubtypes(): List<InputMethodModel.Subtype>

    /**
     * Shows the system's input method picker dialog.
     *
     * @param displayId The display ID on which to show the dialog.
     * @param showAuxiliarySubtypes Whether to show auxiliary input method subtypes in the list of
     *   enabled IMEs.
     * @see InputMethodManager.showInputMethodPickerFromSystem
     */
    suspend fun showInputMethodPicker(displayId: Int, showAuxiliarySubtypes: Boolean)
}

@SysUISingleton
class InputMethodRepositoryImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val inputMethodManager: InputMethodManager,
) : InputMethodRepository {

    override suspend fun enabledInputMethods(
        userId: Int,
        fetchSubtypes: Boolean
    ): Flow<InputMethodModel> {
        return withContext(backgroundDispatcher) {
                inputMethodManager.getEnabledInputMethodListAsUser(UserHandle.of(userId))
            }
            .asFlow()
            .map { inputMethodInfo ->
                InputMethodModel(
                    imeId = inputMethodInfo.id,
                    subtypes =
                        if (fetchSubtypes) {
                            enabledInputMethodSubtypes(
                                inputMethodInfo,
                                allowsImplicitlyEnabledSubtypes = true
                            )
                        } else {
                            listOf()
                        }
                )
            }
    }

    override suspend fun selectedInputMethodSubtypes(): List<InputMethodModel.Subtype> {
        return enabledInputMethodSubtypes(
            inputMethodInfo = null, // Fetch subtypes for the currently-selected IME.
            allowsImplicitlyEnabledSubtypes = false
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun showInputMethodPicker(displayId: Int, showAuxiliarySubtypes: Boolean) {
        withContext(backgroundDispatcher) {
            inputMethodManager.showInputMethodPickerFromSystem(showAuxiliarySubtypes, displayId)
        }
    }

    /**
     * Returns a list of enabled input method subtypes for the specified input method info.
     *
     * @param inputMethodInfo The [InputMethodInfo] whose subtypes list will be returned. If `null`,
     *   returns enabled subtypes for the currently selected [InputMethodInfo].
     * @param allowsImplicitlyEnabledSubtypes Whether to allow to return the implicitly enabled
     *   subtypes. If an input method info doesn't have enabled subtypes, the framework will
     *   implicitly enable subtypes according to the current system language.
     * @see InputMethodManager.getEnabledInputMethodSubtypeList
     */
    private suspend fun enabledInputMethodSubtypes(
        inputMethodInfo: InputMethodInfo?,
        allowsImplicitlyEnabledSubtypes: Boolean
    ): List<InputMethodModel.Subtype> {
        return withContext(backgroundDispatcher) {
                inputMethodManager.getEnabledInputMethodSubtypeList(
                    inputMethodInfo,
                    allowsImplicitlyEnabledSubtypes
                )
            }
            .map {
                InputMethodModel.Subtype(
                    subtypeId = it.subtypeId,
                    isAuxiliary = it.isAuxiliary,
                )
            }
    }
}

@Module
interface InputMethodRepositoryModule {
    @Binds fun repository(impl: InputMethodRepositoryImpl): InputMethodRepository
}
