/*
 * Copyright (C) 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.notetask.quickaffordance

import android.test.suitebuilder.annotation.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.LockScreenState
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskController.ShowNoteTaskUiEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Tests for [NoteTaskQuickAffordanceConfig].
 *
 * Build/Install/Run:
 * - atest SystemUITests:NoteTaskQuickAffordanceConfigTest
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock lateinit var noteTaskController: NoteTaskController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    private fun createUnderTest(isEnabled: Boolean) =
        NoteTaskQuickAffordanceConfig(
            context = context,
            noteTaskController = noteTaskController,
            isEnabled = isEnabled,
        )

    @Test
    fun lockScreenState_isNotEnabled_shouldEmitHidden() = runTest {
        val underTest = createUnderTest(isEnabled = false)

        val actual = collectLastValue(underTest.lockScreenState)

        assertThat(actual()).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_isEnabled_shouldEmitVisible() = runTest {
        val stringResult = "Notetaking"
        val underTest = createUnderTest(isEnabled = true)

        val actual = collectLastValue(underTest.lockScreenState)

        val expected =
            LockScreenState.Visible(
                icon =
                    Icon.Resource(
                        res = R.drawable.ic_note_task_shortcut_keyguard,
                        contentDescription = ContentDescription.Loaded(stringResult),
                    )
            )
        assertThat(actual()).isEqualTo(expected)
    }

    @Test
    fun onTriggered_shouldLaunchNoteTask() {
        val underTest = createUnderTest(isEnabled = false)

        underTest.onTriggered(expandable = null)

        verify(noteTaskController)
            .showNoteTask(uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE)
    }
}
