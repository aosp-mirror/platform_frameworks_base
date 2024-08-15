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
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
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

    private val spiedContext = spy(context)
    private val spiedResources = spy(spiedContext.resources)

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

        `when`(spiedContext.resources).thenReturn(spiedResources)
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    private fun createUnderTest(isEnabled: Boolean = true): KeyguardQuickAffordanceConfig =
        NoteTaskQuickAffordanceConfig(
            context = spiedContext,
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
    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userLocked_customizationDisabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userLocked_customizationDisabled_notesLockScreenShortcutSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userLocked_customizationEnabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userLocked_customizationEnabled_notesLockScreenShortcutSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userUnlocked_customizationDisabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userUnlocked_customizationDisabled_notesLockScreenShortcutSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userUnlocked_customizationEnabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUnused_userUnlocked_customizationEnabled_notesLockScreenShortcutSelected_shouldEmitVisible() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(false)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(createLockScreenStateVisible())
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userLocked_customizationDisabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userLocked_customizationDisabled_notesLockScreenShortcutSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userLocked_customizationEnabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userLocked_customizationEnabled_notesLockScreenShortcutSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(false)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userUnlocked_customizationDisabled_notesLockScreenShortcutNotSelected_shouldEmitVisible() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(createLockScreenStateVisible())
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userUnlocked_customizationDisabled_notesLockScreenShortcutSelected_shouldEmitVisible() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(false)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(createLockScreenStateVisible())
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userUnlocked_customizationEnabled_notesLockScreenShortcutNotSelected_shouldEmitHidden() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections()

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(LockScreenState.Hidden)
        }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun lockScreenState_stylusUsed_userUnlocked_customizationEnabled_notesLockScreenShortcutSelected_shouldEmitVisible() =
        runTest {
            val underTest = createUnderTest()
            TestConfig()
                .setStylusEverUsed(true)
                .setUserUnlocked(true)
                .setLockScreenCustomizationEnabled(true)
                .setConfigSelections(underTest)

            val actual by collectLastValue(underTest.lockScreenState)

            assertThat(actual).isEqualTo(createLockScreenStateVisible())
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
            whenever(InputSettings.isStylusEverUsed(spiedContext)).thenReturn(value)
        }

        fun setUserUnlocked(value: Boolean) = also {
            whenever(userManager.isUserUnlocked).thenReturn(value)
        }

        fun setLockScreenCustomizationEnabled(value: Boolean) = also {
            `when`(spiedResources.getBoolean(R.bool.custom_lockscreen_shortcuts_enabled))
                .thenReturn(value)
        }

        fun setConfigSelections(vararg values: KeyguardQuickAffordanceConfig) = also {
            val slotKey = "bottom-right"
            val configSnapshots = values.toList()
            val map = mapOf(slotKey to configSnapshots)
            whenever(repository.selections).thenReturn(MutableStateFlow(map))
        }
    }
}
