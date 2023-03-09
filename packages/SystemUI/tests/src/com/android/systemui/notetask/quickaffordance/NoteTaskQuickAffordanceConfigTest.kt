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

import android.hardware.input.InputSettings
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.LockScreenState
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.stylus.StylusManager
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoSession
import org.mockito.quality.Strictness

/** atest SystemUITests:NoteTaskQuickAffordanceConfigTest */
@SmallTest
@RunWith(AndroidTestingRunner::class)
internal class NoteTaskQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock lateinit var controller: NoteTaskController
    @Mock lateinit var stylusManager: StylusManager
    @Mock lateinit var repository: KeyguardQuickAffordanceRepository

    private lateinit var mockitoSession: MockitoSession

    @Before
    fun setUp() {
        mockitoSession =
            ExtendedMockito.mockitoSession()
                .initMocks(this)
                .mockStatic(InputSettings::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()

        whenever(InputSettings.isStylusEverUsed(mContext)).then { true }
        whenever(repository.selections).then {
            val map = mapOf("" to listOf(createUnderTest()))
            MutableStateFlow(map)
        }
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    private fun createUnderTest(isEnabled: Boolean = true): KeyguardQuickAffordanceConfig =
        NoteTaskQuickAffordanceConfig(
            context = context,
            controller = controller,
            stylusManager = stylusManager,
            lazyRepository = { repository },
            isEnabled = isEnabled,
        )

    private fun createLockScreenStateVisible(): LockScreenState =
        LockScreenState.Visible(
            icon =
                Icon.Resource(
                    res = R.drawable.ic_note_task_shortcut_keyguard,
                    contentDescription =
                        ContentDescription.Resource(R.string.note_task_button_label),
                )
        )

    @Test
    fun lockScreenState_stylusUsed_noCustomShortcutSelected_shouldEmitVisible() = runTest {
        val underTest = createUnderTest()

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(createLockScreenStateVisible())
    }

    @Test
    fun lockScreenState_noStylusEverUsed_noCustomShortcutSelected_shouldEmitVisible() = runTest {
        whenever(InputSettings.isStylusEverUsed(mContext)).then { false }
        val underTest = createUnderTest()

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(createLockScreenStateVisible())
    }

    @Test
    fun lockScreenState_stylusUsed_customShortcutSelected_shouldEmitVisible() = runTest {
        whenever(repository.selections).then {
            val map = mapOf<String, List<KeyguardQuickAffordanceConfig>>()
            MutableStateFlow(map)
        }
        val underTest = createUnderTest()

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(createLockScreenStateVisible())
    }

    @Test
    fun lockScreenState_noIsStylusEverUsed_noCustomShortcutSelected_shouldEmitHidden() = runTest {
        whenever(InputSettings.isStylusEverUsed(mContext)).then { false }
        whenever(repository.selections).then {
            val map = mapOf<String, List<KeyguardQuickAffordanceConfig>>()
            MutableStateFlow(map)
        }
        val underTest = createUnderTest()

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_isNotEnabled_shouldEmitHidden() = runTest {
        val underTest = createUnderTest(isEnabled = false)

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun onTriggered_shouldLaunchNoteTask() {
        val underTest = createUnderTest(isEnabled = false)

        underTest.onTriggered(expandable = null)

        verify(controller).showNoteTask(entryPoint = NoteTaskEntryPoint.QUICK_AFFORDANCE)
    }
}
