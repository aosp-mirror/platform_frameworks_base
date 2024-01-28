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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputmethod.data.model.InputMethodModel
import com.android.systemui.inputmethod.data.repository.fakeInputMethodRepository
import com.android.systemui.inputmethod.data.repository.inputMethodRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class InputMethodInteractorTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val fakeInputMethodRepository = kosmos.fakeInputMethodRepository

    private val underTest = InputMethodInteractor(repository = kosmos.inputMethodRepository)

    @Test
    fun hasMultipleEnabledImesOrSubtypes_noImes_returnsFalse() =
        testScope.runTest {
            fakeInputMethodRepository.setEnabledInputMethods(USER_ID)

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isFalse()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_noMatches_returnsFalse() =
        testScope.runTest {
            fakeInputMethodRepository.setEnabledInputMethods(
                USER_ID,
                createInputMethodWithSubtypes(auxiliarySubtypes = 1, nonAuxiliarySubtypes = 0),
                createInputMethodWithSubtypes(auxiliarySubtypes = 3, nonAuxiliarySubtypes = 0),
            )

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isFalse()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_oneMatch_returnsFalse() =
        testScope.runTest {
            fakeInputMethodRepository.setEnabledInputMethods(
                USER_ID,
                createInputMethodWithSubtypes(auxiliarySubtypes = 0, nonAuxiliarySubtypes = 0),
            )

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isFalse()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_twoMatches_returnsTrue() =
        testScope.runTest {
            fakeInputMethodRepository.setEnabledInputMethods(
                USER_ID,
                createInputMethodWithSubtypes(auxiliarySubtypes = 0, nonAuxiliarySubtypes = 1),
                createInputMethodWithSubtypes(auxiliarySubtypes = 0, nonAuxiliarySubtypes = 0),
            )

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isTrue()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_oneWithNonAux_returnsFalse() =
        testScope.runTest {
            fakeInputMethodRepository.setEnabledInputMethods(
                USER_ID,
                createInputMethodWithSubtypes(auxiliarySubtypes = 0, nonAuxiliarySubtypes = 2),
            )

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isFalse()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_twoWithAux_returnsFalse() =
        testScope.runTest {
            fakeInputMethodRepository.setEnabledInputMethods(
                USER_ID,
                createInputMethodWithSubtypes(auxiliarySubtypes = 3, nonAuxiliarySubtypes = 0),
                createInputMethodWithSubtypes(auxiliarySubtypes = 5, nonAuxiliarySubtypes = 0),
            )

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isFalse()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_selectedHasOneSubtype_returnsFalse() =
        testScope.runTest {
            fakeInputMethodRepository.selectedInputMethodSubtypes =
                listOf(InputMethodModel.Subtype(1, isAuxiliary = false))

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isFalse()
        }

    @Test
    fun hasMultipleEnabledImesOrSubtypes_selectedHasTwoSubtypes_returnsTrue() =
        testScope.runTest {
            fakeInputMethodRepository.selectedInputMethodSubtypes =
                listOf(
                    InputMethodModel.Subtype(subtypeId = 1, isAuxiliary = false),
                    InputMethodModel.Subtype(subtypeId = 2, isAuxiliary = false),
                )

            assertThat(underTest.hasMultipleEnabledImesOrSubtypes(USER_ID)).isTrue()
        }

    @Test
    fun showImePicker_shownOnCorrectId() =
        testScope.runTest {
            val displayId = 7

            underTest.showInputMethodPicker(displayId, showAuxiliarySubtypes = false)

            assertThat(fakeInputMethodRepository.inputMethodPickerShownDisplayId)
                .isEqualTo(displayId)
        }

    private fun createInputMethodWithSubtypes(
        auxiliarySubtypes: Int,
        nonAuxiliarySubtypes: Int,
    ): InputMethodModel {
        return InputMethodModel(
            imeId = UUID.randomUUID().toString(),
            subtypes =
                List(auxiliarySubtypes + nonAuxiliarySubtypes) {
                    InputMethodModel.Subtype(subtypeId = it, isAuxiliary = it < auxiliarySubtypes)
                }
        )
    }

    companion object {
        private const val USER_ID = 100
    }
}
