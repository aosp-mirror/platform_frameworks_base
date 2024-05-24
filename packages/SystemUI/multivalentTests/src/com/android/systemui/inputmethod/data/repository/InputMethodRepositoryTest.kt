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

import android.os.UserHandle
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class InputMethodRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var inputMethodManager: InputMethodManager

    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: InputMethodRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(inputMethodManager.getEnabledInputMethodSubtypeList(eq(null), anyBoolean()))
            .thenReturn(listOf())

        underTest =
            InputMethodRepositoryImpl(
                backgroundDispatcher = kosmos.testDispatcher,
                inputMethodManager = inputMethodManager,
            )
    }

    @Test
    fun enabledInputMethods_noImes_emptyFlow() =
        testScope.runTest {
            whenever(inputMethodManager.getEnabledInputMethodListAsUser(eq(USER_HANDLE)))
                .thenReturn(listOf())
            whenever(inputMethodManager.getEnabledInputMethodSubtypeList(any(), anyBoolean()))
                .thenReturn(listOf())

            assertThat(underTest.enabledInputMethods(USER_ID, fetchSubtypes = true).count())
                .isEqualTo(0)
        }

    @Test
    fun selectedInputMethodSubtypes_returnsSubtypeList() =
        testScope.runTest {
            val subtypeId = 123
            val isAuxiliary = true
            whenever(inputMethodManager.getEnabledInputMethodListAsUser(eq(USER_HANDLE)))
                .thenReturn(listOf(mock<InputMethodInfo>()))
            whenever(inputMethodManager.getEnabledInputMethodSubtypeList(any(), anyBoolean()))
                .thenReturn(listOf())
            whenever(inputMethodManager.getEnabledInputMethodSubtypeList(eq(null), anyBoolean()))
                .thenReturn(
                    listOf(
                        InputMethodSubtype.InputMethodSubtypeBuilder()
                            .setSubtypeId(subtypeId)
                            .setIsAuxiliary(isAuxiliary)
                            .build()
                    )
                )

            val result = underTest.selectedInputMethodSubtypes()
            assertThat(result).hasSize(1)
            assertThat(result.first().subtypeId).isEqualTo(subtypeId)
            assertThat(result.first().isAuxiliary).isEqualTo(isAuxiliary)
        }

    @Test
    fun showImePicker_forwardsDisplayId() =
        testScope.runTest {
            val displayId = 7

            underTest.showInputMethodPicker(displayId, /* showAuxiliarySubtypes = */ true)

            verify(inputMethodManager)
                .showInputMethodPickerFromSystem(
                    /* showAuxiliarySubtypes = */ eq(true),
                    /* displayId = */ eq(displayId)
                )
        }

    companion object {
        private const val USER_ID = 100
        private val USER_HANDLE = UserHandle.of(USER_ID)
    }
}
