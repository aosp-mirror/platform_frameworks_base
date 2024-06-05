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

import android.app.role.RoleManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.hardware.input.InputSettings
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.LockScreenState
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.notetask.LaunchNotesRoleSettingsTrampolineActivity.Companion.ACTION_MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.notetask.NoteTaskInfoResolver
import com.android.systemui.res.R
import com.android.systemui.stylus.StylusManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.verify
import org.mockito.MockitoSession
import org.mockito.quality.Strictness

/** atest SystemUITests:NoteTaskQuickAffordanceConfigTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock lateinit var controller: NoteTaskController
    @Mock lateinit var stylusManager: StylusManager
    @Mock lateinit var repository: KeyguardQuickAffordanceRepository
    @Mock lateinit var userManager: UserManager
    @Mock lateinit var roleManager: RoleManager
    @Mock lateinit var packageManager: PackageManager

    private lateinit var mockitoSession: MockitoSession

    @Before
    fun setUp() {
        mockitoSession =
            ExtendedMockito.mockitoSession()
                .initMocks(this)
                .mockStatic(InputSettings::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()

        whenever(
                packageManager.getApplicationInfoAsUser(
                    anyString(),
                    any(ApplicationInfoFlags::class.java),
                    any(UserHandle::class.java)
                )
            )
            .thenReturn(ApplicationInfo())
        whenever(controller.getUserForHandlingNotesTaking(any())).thenReturn(UserHandle.SYSTEM)
        whenever(
                roleManager.getRoleHoldersAsUser(
                    eq(RoleManager.ROLE_NOTES),
                    any(UserHandle::class.java)
                )
            )
            .thenReturn(listOf("com.google.test.notes"))
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
            userManager = userManager,
            keyguardMonitor = mock(),
            lazyRepository = { repository },
            isEnabled = isEnabled,
            backgroundExecutor = FakeExecutor(FakeSystemClock()),
            roleManager = roleManager,
            noteTaskInfoResolver = NoteTaskInfoResolver(roleManager, packageManager)
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

    // region lockScreenState
    @Test
    fun lockScreenState_stylusUsed_userUnlocked_isSelected_shouldEmitVisible() = runTest {
        val underTest = createUnderTest()
        TestConfig().setStylusEverUsed(true).setUserUnlocked(true).setConfigSelections(underTest)

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(createLockScreenStateVisible())
    }

    @Test
    fun lockScreenState_stylusUsed_userUnlocked_isSelected_noDefaultNotesAppSet_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(true)
                .setConfigSelections(underTest)
            whenever(
                    roleManager.getRoleHoldersAsUser(
                        eq(RoleManager.ROLE_NOTES),
                        any(UserHandle::class.java)
                    )
                )
                .thenReturn(emptyList())

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Test
    fun lockScreenState_stylusUnused_userUnlocked_isSelected_shouldEmitHidden() = runTest {
        val underTest = createUnderTest()
        TestConfig().setStylusEverUsed(false).setUserUnlocked(true).setConfigSelections(underTest)

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUsed_userLocked_isSelected_shouldEmitHidden() = runTest {
        val underTest = createUnderTest()
        TestConfig().setStylusEverUsed(true).setUserUnlocked(false).setConfigSelections(underTest)

        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUsed_userUnlocked_noSelected_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(true).setUserUnlocked(true).setConfigSelections()

        val underTest = createUnderTest()
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUnused_userUnlocked_noSelected_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(false).setUserUnlocked(true).setConfigSelections()

        val underTest = createUnderTest()
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUsed_userLocked_noSelected_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(true).setUserUnlocked(false).setConfigSelections()

        val underTest = createUnderTest()
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUsed_userUnlocked_customSelections_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(true).setUserUnlocked(true).setConfigSelections(mock())

        val underTest = createUnderTest()
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUnused_userUnlocked_customSelections_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(false).setUserUnlocked(true).setConfigSelections(mock())

        val underTest = createUnderTest()
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_stylusUsed_userLocked_customSelections_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(true).setUserUnlocked(false).setConfigSelections(mock())

        val underTest = createUnderTest()
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }

    @Test
    fun lockScreenState_isNotEnabled_shouldEmitHidden() = runTest {
        TestConfig().setStylusEverUsed(true).setUserUnlocked(true).setConfigSelections()

        val underTest = createUnderTest(isEnabled = false)
        val actual by collectLastValue(underTest.lockScreenState)

        assertThat(actual).isEqualTo(LockScreenState.Hidden)
    }
    // endregion

    @Test
    fun onTriggered_shouldLaunchNoteTask() {
        val underTest = createUnderTest(isEnabled = false)

        underTest.onTriggered(expandable = null)

        verify(controller).showNoteTask(entryPoint = NoteTaskEntryPoint.QUICK_AFFORDANCE)
    }

    // region getPickerScreenState
    @Test
    fun getPickerScreenState_defaultNoteAppSet_shouldReturnDefault() = runTest {
        val underTest = createUnderTest(isEnabled = true)

        assertThat(underTest.getPickerScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.Default())
    }

    @Test
    fun getPickerScreenState_noDefaultNoteAppSet_shouldReturnDisabled() = runTest {
        val underTest = createUnderTest(isEnabled = true)
        whenever(
                roleManager.getRoleHoldersAsUser(
                    eq(RoleManager.ROLE_NOTES),
                    any(UserHandle::class.java)
                )
            )
            .thenReturn(emptyList())

        val pickerScreenState = underTest.getPickerScreenState()
        assertThat(pickerScreenState is KeyguardQuickAffordanceConfig.PickerScreenState.Disabled)
            .isTrue()
        val disabled = pickerScreenState as KeyguardQuickAffordanceConfig.PickerScreenState.Disabled
        assertThat(disabled.explanation)
            .isEqualTo("Select a default notes app to use the notetaking shortcut")
        assertThat(disabled.actionText).isEqualTo("Select app")
        assertThat(disabled.actionIntent?.action)
            .isEqualTo(ACTION_MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE)
        assertThat(disabled.actionIntent?.`package`).isEqualTo(context.packageName)
    }
    // endregion

    private inner class TestConfig {

        fun setStylusEverUsed(value: Boolean) = also {
            whenever(InputSettings.isStylusEverUsed(mContext)).thenReturn(value)
        }

        fun setUserUnlocked(value: Boolean) = also {
            whenever(userManager.isUserUnlocked).thenReturn(value)
        }

        fun setConfigSelections(vararg values: KeyguardQuickAffordanceConfig) = also {
            val slotKey = "bottom-right"
            val configSnapshots = values.toList()
            val map = mapOf(slotKey to configSnapshots)
            whenever(repository.selections).thenReturn(MutableStateFlow(map))
        }
    }
}
